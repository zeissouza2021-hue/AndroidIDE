/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.aiagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.agent.AgentEngine
import dev.mutwakil.androidide.aiagent.agent.AgentEvent
import dev.mutwakil.androidide.aiagent.agent.EditHunk
import dev.mutwakil.androidide.aiagent.agent.EditReviewDecision
import dev.mutwakil.androidide.aiagent.agent.EditReviewRequest
import dev.mutwakil.androidide.aiagent.agent.PermissionManager
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.gate.CapabilityGate
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ChatMessage
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.model.Role
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in the chat message list. */
sealed interface ChatListItem {
  data class User(val text: String, val attachments: List<Attachment>) : ChatListItem
  data class Assistant(val text: String, val isStreaming: Boolean) : ChatListItem
  data class Status(val text: String) : ChatListItem
  data class PlanItem(val steps: List<String>, val doneCount: Int) : ChatListItem
  data class ToolEvent(val name: String, val ok: Boolean?) : ChatListItem
  data class Error(val text: String) : ChatListItem
  /**
   * v2 diff-review card: proposed `edit_file` hunks awaiting the user's
   * decision (apply all / some / discard).
   */
  data class DiffProposal(
    val id: String,
    val path: String,
    val hunks: List<EditHunk>,
    val state: DiffProposalState
  ) : ChatListItem
}

/** Full UI state for the AI chat screen. */
data class ChatUiState(
  val messages: List<ChatListItem> = emptyList(),
  val isBusy: Boolean = false,
  val activeProvider: AiProviderPlugin? = null,
  /**
   * Capabilities of [activeProvider], resolved off the main thread
   * (reading them touches EncryptedSharedPreferences/keystore).
   */
  val capabilities: Set<Capability> = emptySet(),
  val pendingAttachments: List<Attachment> = emptyList(),
  /** v2: o painel abre na aba Perguntar; Agente (com tools) é opt-in. */
  val mode: AgentMode = AgentMode.CHAT,
  /** v2: id da entrada de provider ativa (padrão ou personalizada). */
  val activeConfigId: String? = null,
  /** v2: chips de contexto automático (arquivo, seleção, erro anexado). */
  val chips: List<ChatContextChip> = emptyList()
)

/**
 * Holds the [AgentEngine] for the current turn and exposes the chat UI state.
 *
 * Destructive tool confirmations are routed through [confirmHandler], which the
 * Fragment sets to a `MaterialAlertDialogBuilder`-backed suspend dialog and which
 * feeds [PermissionManager.confirmCallback].
 */
class AiChatViewModel(
  application: Application,
  projectPath: String?,
  private val windowId: String? = null
) : AndroidViewModel(application) {

  private val projectRoot: File =
    projectPath?.let(::File)?.takeIf { it.exists() } ?: application.filesDir

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  private val permissions = PermissionManager()
  private val windowPersistence = windowId?.let { AiWindowPersistence(application) }

  /**
   * Set by the Fragment. Must show a confirmation dialog on the main thread and
   * return the user's choice. Defaults to deny when unset.
   */
  var confirmHandler: (suspend (title: String, detail: String) -> Boolean)? = null

  private var currentEngine: AgentEngine? = null
  private var currentJob: Job? = null

  /** Conversation transcript owned by this ViewModel/window, not the global provider store. */
  private val conversationHistory = mutableListOf<AiPersistedTurn>()

  /** Pending diff reviews: card id -> decision the engine is suspended on. */
  private val diffDecisions = mutableMapOf<String, CompletableDeferred<EditReviewDecision>>()

  init {
    AiAgent.init(application)
    AiChatV2Providers.ensureValidState()
    permissions.confirmCallback = { title, detail ->
      confirmHandler?.invoke(title, detail) == true
    }
    // v2: edit_file vira revisão de diff no chat (card aplicar/descartar).
    permissions.confirmEditDiff = { request -> reviewEditDiff(request) }
    val initial = resolveInitialConfig()
    _uiState.update {
      it.copy(
        activeProvider = initial?.first,
        activeConfigId = initial?.second?.configId
      )
    }
    val persistedMode = windowId?.let { id ->
      runCatching { windowPersistence?.loadMode(id) }.getOrNull()
    }
    if (persistedMode != null) {
      _uiState.update { it.copy(mode = persistedMode) }
    }
    if (!windowId.isNullOrBlank()) {
      conversationHistory.addAll(
        runCatching {
          windowId?.let { id -> windowPersistence?.loadConversation(id).orEmpty() }.orEmpty()
        }.getOrDefault(emptyList())
      )
      val restoredMessages = conversationHistory.mapNotNull { turn ->
        when (turn.message.role) {
          Role.USER -> ChatListItem.User(turn.displayText, turn.message.attachments)
          Role.ASSISTANT -> ChatListItem.Assistant(turn.displayText, isStreaming = false)
          else -> null
        }
      }
      if (restoredMessages.isNotEmpty()) {
        _uiState.update { it.copy(messages = restoredMessages) }
      }
    }
    refreshCapabilities()
  }

  private fun persistConversation() {
    val id = windowId ?: return
    runCatching {
      windowPersistence?.saveConversation(id, conversationHistory)
    }
  }

  private fun trimConversationHistory() {
    while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
      conversationHistory.removeAt(0)
    }
  }

  /**
   * Switches the active provider for this session (legacy entry point used by
   * the old activity's provider menu; maps to the protocol's default entry).
   */
  fun setActiveProvider(plugin: AiProviderPlugin) {
    if (_uiState.value.isBusy) return
    val config = AiChatV2Providers.defaultConfigFor(plugin.id)
      ?: AiChatV2Providers.listConfigs().firstOrNull { it.providerId == plugin.id }
      ?: return
    setActiveConfig(config.configId)
  }

  /** Switches the active provider entry (v2 model selector in the panel header). */
  fun setActiveConfig(configId: String) {
    if (_uiState.value.isBusy) return
    val (plugin, config) = AiChatV2Providers.resolveConfig(configId) ?: return
    // A seleção feita nesta janela é local à sessão. O provider global salvo
    // em ProviderConfigStore continua servindo apenas como padrão para novas
    // janelas; mudar o modelo aqui não deve alterar as outras conversas abertas.
    _uiState.update { it.copy(activeProvider = plugin, activeConfigId = config.configId) }
    refreshCapabilities()
  }

  /**
   * Re-resolves the active entry's capabilities off the main thread.
   * Reading them touches EncryptedSharedPreferences (keystore), so this must
   * never run per-render — previously it ran on every streamed token.
   * v2: applies the entry's capability overrides when present.
   */
  fun refreshCapabilities() {
    val plugin = _uiState.value.activeProvider
    val config = _uiState.value.activeConfigId?.let {
      AiAgent.configStore().getProviderConfig(it)
    }
    if (plugin == null) {
      _uiState.update { it.copy(capabilities = emptySet()) }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val caps = AiChatV2Capabilities.resolve(plugin, config)
      _uiState.update { it.copy(capabilities = caps) }
    }
  }

  /** Switches between AGENT (tools enabled) and CHAT (conversation only) modes. */
  fun setMode(mode: AgentMode) {
    if (_uiState.value.isBusy) return
    _uiState.update { it.copy(mode = mode) }
    windowId?.let { id ->
      runCatching { windowPersistence?.saveMode(id, mode) }
    }
  }

  fun addPendingAttachment(attachment: Attachment) {
    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
  }

  fun removePendingAttachment(attachment: Attachment) {
    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - attachment) }
  }

  /**
   * Validates capabilities/config, then runs the message through the agent engine.
   *
   * v2: context chips are consumed by this message (prepended to what the
   * model sees, then cleared), and a retryable provider failure
   * (rate-limit/overload/context-overflow) automatically retries with the
   * next fallback-enabled entry, posting a visible "switched models" warning.
   */
  fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
    val state = _uiState.value
    if (state.isBusy) return
    val trimmed = text.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return

    val app = getApplication<Application>()
    val (plugin, config) = state.activeConfigId
      ?.let { AiChatV2Providers.resolveConfig(it) }
      ?: run {
        addError(app.getString(R.string.aiagent_no_providers))
        return
      }

    // Claim the busy flag synchronously on the caller thread (main), before
    // the coroutine starts: otherwise a second send can slip through the
    // isBusy check above while the first coroutine is still starting up,
    // spawning two engines for a single chat.
    _uiState.update { it.copy(isBusy = true) }

    currentJob = viewModelScope.launch {
      val caps = AiChatV2Capabilities.resolve(plugin, config)

      // Capability gate: never send an attachment the provider cannot handle.
      for (attachment in attachments) {
        val missing = CapabilityGate.missingCapability(caps, attachment)
        if (missing != null) {
          addError(CapabilityGate.unavailableMessage(plugin.displayName, missing))
          _uiState.update { it.copy(isBusy = false) }
          return@launch
        }
      }

      // Contexto automático: os chips são consumidos nesta mensagem —
      // prefixados ao texto que o modelo vê — e depois limpos.
      val chips = _uiState.value.chips
      val fullText = AiChatV2Context.formatContextBlock(chips) + trimmed
      val currentMessage = ChatMessage(Role.USER, fullText, attachments)
      // Copy prior turns before adding the current turn. Every fallback attempt
      // receives the same transcript and cannot duplicate the user's message.
      val priorMessages = conversationHistory.takeLast(MAX_HISTORY_MESSAGES).map { it.message }
      conversationHistory.add(AiPersistedTurn(currentMessage, trimmed))
      persistConversation()

      _uiState.update { current ->
        val withUser = current.copy(
          pendingAttachments = emptyList(),
          chips = emptyList(),
          messages = current.messages + ChatListItem.User(trimmed, attachments)
        )
        if (chips.isEmpty()) {
          withUser
        } else {
          withUser.copy(
            messages = withUser.messages + ChatListItem.Status(
              app.getString(
                R.string.ai_chat_v2_context_attached,
                chips.joinToString(", ") { chip -> chip.label }
              )
            )
          )
        }
      }

      try {
        var targetPlugin = plugin
        var targetConfig = config
        var successfulReply: String? = null
        var terminalFailure: String? = null
        val tried = mutableSetOf(config.configId)
        while (true) {
          val targetCaps = AiChatV2Capabilities.resolve(targetPlugin, targetConfig)
          val historyForTarget = priorMessages.map { previous ->
            if (previous.attachments.isEmpty()) {
              previous
            } else {
              val supported = previous.attachments.filter {
                CapabilityGate.missingCapability(targetCaps, it) == null
              }
              val omitted = previous.attachments - supported.toSet()
              previous.copy(
                text = if (omitted.isEmpty()) previous.text else {
                  previous.text + "\n\n[Anexos anteriores omitidos para este modelo: " +
                    omitted.joinToString(", ") { it.displayName } + "]"
                },
                attachments = supported
              )
            }
          }
          val engine = AiAgent.newEngine(
            targetPlugin, targetConfig, projectRoot, state.mode,
            permissions = permissions
          )
          currentEngine = engine
          var failure: AgentEvent.Failed? = null
          val streamedReply = StringBuilder()
          var completedReply: String? = null
          try {
            engine.run(currentMessage, historyForTarget).collect { event ->
              when (event) {
                is AgentEvent.AssistantDelta -> streamedReply.append(event.text)
                is AgentEvent.Completed -> completedReply = event.summary
                else -> Unit
              }
              if (event is AgentEvent.Failed) failure = event else handleEvent(event)
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            failure = AgentEvent.Failed(
              e.message ?: app.getString(R.string.aiagent_error_generic)
            )
          } finally {
            currentEngine = null
          }

          val failed = failure
          if (failed == null) {
            successfulReply = completedReply ?: streamedReply.toString()
            break
          }
          val reason = AiChatV2Fallback.classify(failed.error)
          val next = reason?.let {
            AiChatV2Fallback.candidates(AiAgent.configStore(), targetConfig.configId)
              .firstOrNull { candidate ->
                candidate.config.configId !in tried &&
                  attachments.all { attachment ->
                    CapabilityGate.missingCapability(
                      AiChatV2Capabilities.resolve(candidate.plugin, candidate.config),
                      attachment
                    ) == null
                  }
              }
          }
          if (next == null) {
            terminalFailure = failed.error
            handleEvent(failed)
            break
          }
          tried += next.config.configId
          targetPlugin = next.plugin
          targetConfig = next.config
          appendItem(
            ChatListItem.Status(
              app.getString(
                R.string.ai_chat_v2_fallback_switched,
                next.config.displayName,
                reasonLabel(reason)
              )
            )
          )
          // A sessão acompanha a troca: seletor, gating e a próxima
          // mensagem usam a nova entrada.
          _uiState.update {
            it.copy(activeProvider = next.plugin, activeConfigId = next.config.configId)
          }
          refreshCapabilities()
        }
        if (successfulReply != null) {
          val reply = successfulReply.orEmpty()
          conversationHistory.add(
            AiPersistedTurn(ChatMessage(Role.ASSISTANT, reply), reply)
          )
          trimConversationHistory()
          persistConversation()
        } else if (terminalFailure != null) {
          val errorReply = "A execução falhou: " + terminalFailure.orEmpty()
          conversationHistory.add(
            AiPersistedTurn(ChatMessage(Role.ASSISTANT, errorReply), errorReply)
          )
          trimConversationHistory()
          persistConversation()
        }
      } finally {
        currentEngine = null
        _uiState.update { it.copy(isBusy = false) }
      }
    }
  }

  /** Cancels the in-flight engine run. */
  fun cancel() {
    // Unblocks the engine if it is suspended on a diff review.
    synchronized(diffDecisions) {
      diffDecisions.values.forEach { it.complete(EditReviewDecision.Discard) }
      diffDecisions.clear()
    }
    currentEngine?.cancel()
    currentJob?.cancel()
    finalizeStreaming()
    appendItem(ChatListItem.Status(getApplication<Application>().getString(R.string.aiagent_cancelled)))
    _uiState.update { it.copy(isBusy = false) }
  }

  /** Appends an informational status row (e.g. mode changes). */
  fun postStatus(text: String) {
    appendItem(ChatListItem.Status(text))
  }

  /**
   * Seeds the automatic context chips (open file + current selection).
   * Called once by the host with the panel/activity arguments.
   */
  fun setInitialContext(filePath: String?, selection: String?) {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      val chips = mutableListOf<ChatContextChip>()
      if (!filePath.isNullOrBlank()) {
        val file = File(filePath)
        if (file.isFile && file.length() <= MAX_CHIP_FILE_BYTES) {
          val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
          if (!content.isNullOrBlank()) {
            chips += ChatContextChip(
              AiChatV2Context.CHIP_FILE,
              ChatContextChip.Kind.FILE,
              app.getString(R.string.ai_chat_v2_chip_file, file.name),
              content.take(MAX_CHIP_CHARS)
            )
          }
        }
      }
      if (!selection.isNullOrBlank()) {
        chips += ChatContextChip(
          AiChatV2Context.CHIP_SELECTION,
          ChatContextChip.Kind.SELECTION,
          app.getString(R.string.ai_chat_v2_chip_selection),
          selection.take(MAX_CHIP_CHARS)
        )
      }
      if (chips.isNotEmpty()) {
        _uiState.update { it.copy(chips = chips) }
      }
    }
  }

  /**
   * "Anexar erro": pastes recent build/logcat output as a removable chip.
   * No-op when there is no build bridge or no recent logs.
   */
  fun addErrorChip() {
    if (_uiState.value.isBusy) return
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      val logs = runCatching {
        AiAgent.buildBridgeProvider?.get()?.recentLogs(MAX_ERROR_LINES)
      }.getOrNull().orEmpty()
      if (logs.isEmpty()) {
        postStatus(app.getString(R.string.ai_chat_v2_no_build_logs))
        return@launch
      }
      val chip = ChatContextChip(
        AiChatV2Context.CHIP_ERROR,
        ChatContextChip.Kind.ERROR,
        app.getString(R.string.ai_chat_v2_chip_build_error),
        logs.joinToString("\n").take(MAX_CHIP_CHARS)
      )
      _uiState.update { state ->
        state.copy(chips = state.chips.filterNot { it.id == chip.id } + chip)
      }
    }
  }

  /** Removes a context chip (user tapped its close icon). */
  fun removeChip(id: String) {
    _uiState.update { it.copy(chips = it.chips.filterNot { it.id == id }) }
  }

  /**
   * Posts a diff-review card and suspends until the user decides
   * (apply all / some / discard). Runs on the engine's background thread;
   * the card itself is rendered by the UI from [ChatUiState.messages].
   */
  private suspend fun reviewEditDiff(request: EditReviewRequest): EditReviewDecision {
    val id = "diff-" + UUID.randomUUID().toString()
    appendItem(
      ChatListItem.DiffProposal(id, request.path, request.hunks, DiffProposalState.PENDING)
    )
    val deferred = CompletableDeferred<EditReviewDecision>()
    synchronized(diffDecisions) { diffDecisions[id] = deferred }
    return try {
      deferred.await()
    } finally {
      synchronized(diffDecisions) { diffDecisions.remove(id) }
    }
  }

  /**
   * Called by the UI when the user decides on a diff-review card.
   * Updates the card's visual state and unblocks the engine.
   */
  fun resolveDiffProposal(id: String, decision: EditReviewDecision) {
    val effective =
      if (decision is EditReviewDecision.ApplySome && decision.indices.isEmpty()) {
        EditReviewDecision.Discard
      } else {
        decision
      }
    val state = when (effective) {
      EditReviewDecision.ApplyAll -> DiffProposalState.APPLIED
      is EditReviewDecision.ApplySome -> DiffProposalState.PARTIAL
      EditReviewDecision.Discard -> DiffProposalState.DISCARDED
    }
    _uiState.update { current ->
      current.copy(
        messages = current.messages.map {
          if (it is ChatListItem.DiffProposal && it.id == id) it.copy(state = state) else it
        }
      )
    }
    synchronized(diffDecisions) { diffDecisions[id] }?.complete(effective)
  }

  private fun reasonLabel(reason: AiChatV2Fallback.Reason): String {
    val app = getApplication<Application>()
    return when (reason) {
      AiChatV2Fallback.Reason.RATE_LIMITED ->
        app.getString(R.string.ai_chat_v2_fallback_reason_rate_limit)
      AiChatV2Fallback.Reason.OVERLOADED ->
        app.getString(R.string.ai_chat_v2_fallback_reason_overloaded)
      AiChatV2Fallback.Reason.CONTEXT_OVERFLOW ->
        app.getString(R.string.ai_chat_v2_fallback_reason_context)
    }
  }

  private fun resolveInitialConfig(): Pair<AiProviderPlugin, ProviderConfig>? {
    val store = AiAgent.configStore()
    val activeId = store.getActiveConfigId()
    if (activeId != null) {
      AiChatV2Providers.resolveConfig(activeId)?.let { return it }
    }
    return AiChatV2Providers.listConfigs()
      .firstNotNullOfOrNull { AiChatV2Providers.resolveConfig(it.configId) }
  }

  private fun handleEvent(event: AgentEvent) {
    val app = getApplication<Application>()
    when (event) {
      is AgentEvent.Status -> appendItem(ChatListItem.Status(event.text))
      is AgentEvent.AssistantDelta -> appendDelta(event.text)
      is AgentEvent.Plan -> appendItem(ChatListItem.PlanItem(event.steps, doneCount = 0))
      is AgentEvent.ToolStarted -> appendItem(ChatListItem.ToolEvent(event.name, ok = null))
      is AgentEvent.ToolFinished -> markToolFinished(event.name, event.ok)
      is AgentEvent.Completed -> {
        finalizeStreaming()
        // In CHAT mode the streamed assistant bubble already contains the
        // complete response. Repeating it as a status row duplicated every
        // answer and polluted the transcript sent on later turns.
        if (_uiState.value.mode == AgentMode.AGENT && event.summary.isNotBlank()) {
          appendItem(ChatListItem.Status(event.summary))
        }
      }
      is AgentEvent.Failed -> {
        finalizeStreaming()
        appendItem(ChatListItem.Error(event.error))
      }
      AgentEvent.Cancelled -> {
        finalizeStreaming()
        appendItem(ChatListItem.Status(app.getString(R.string.aiagent_cancelled)))
      }
    }
  }

  private fun appendItem(item: ChatListItem) {
    _uiState.update { it.copy(messages = it.messages + item) }
  }

  private fun addError(text: String) {
    appendItem(ChatListItem.Error(text))
  }

  private fun appendDelta(delta: String) {
    _uiState.update { state ->
      val messages = state.messages
      val last = messages.lastOrNull()
      val updated = if (last is ChatListItem.Assistant && last.isStreaming) {
        messages.dropLast(1) + last.copy(text = last.text + delta)
      } else {
        messages + ChatListItem.Assistant(delta, isStreaming = true)
      }
      state.copy(messages = updated)
    }
  }

  private fun finalizeStreaming() {
    _uiState.update { state ->
      val messages = state.messages
      val last = messages.lastOrNull()
      if (last is ChatListItem.Assistant && last.isStreaming) {
        state.copy(messages = messages.dropLast(1) + last.copy(isStreaming = false))
      } else {
        state
      }
    }
  }

  private fun markToolFinished(name: String, ok: Boolean) {    _uiState.update { state ->
      val messages = state.messages
      val index = messages.indexOfLast {
        it is ChatListItem.ToolEvent && it.name == name && it.ok == null
      }
      if (index == -1) {
        state.copy(messages = messages + ChatListItem.ToolEvent(name, ok))
      } else {
        val updated = messages.toMutableList()
        (updated[index] as ChatListItem.ToolEvent).let {
          updated[index] = it.copy(ok = ok)
        }
        // Advance the plan checklist alongside finished tools.
        val planIndex = updated.indexOfLast { it is ChatListItem.PlanItem }
        if (planIndex != -1) {
          (updated[planIndex] as ChatListItem.PlanItem).let { plan ->
            updated[planIndex] = plan.copy(doneCount = (plan.doneCount + 1).coerceAtMost(plan.steps.size))
          }
        }
        state.copy(messages = updated)
      }
    }
  }

  companion object {
    /** Arquivos maiores que isso não viram chip de contexto (512 KiB). */
    const val MAX_CHIP_FILE_BYTES: Long = 512L * 1024

    /** Teto de caracteres por chip de contexto. */
    const val MAX_CHIP_CHARS: Int = 8000

    /** Linhas de log lidas para o chip "anexar erro". */
    const val MAX_ERROR_LINES: Int = 120

    /** Limite de turnos usados como contexto e persistidos por janela. */
    private const val MAX_HISTORY_MESSAGES: Int = 40
  }
}

/** Factory that supplies the [Application] and optional project path to [AiChatViewModel]. */
class AiChatViewModelFactory(
  private val application: Application,
  private val projectPath: String?,
  private val windowId: String? = null
) : ViewModelProvider.Factory {

  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AiChatViewModel::class.java)) {
      return AiChatViewModel(application, projectPath, windowId) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}
