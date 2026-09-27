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
import dev.mutwakil.androidide.aiagent.agent.PermissionManager
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.gate.CapabilityGate
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ChatMessage
import dev.mutwakil.androidide.aiagent.model.Role
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import java.io.File
import kotlinx.coroutines.CancellationException
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
  val mode: AgentMode = AgentMode.CHAT
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
  projectPath: String?
) : AndroidViewModel(application) {

  private val projectRoot: File =
    projectPath?.let(::File)?.takeIf { it.exists() } ?: application.filesDir

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  private val permissions = PermissionManager()

  /**
   * Set by the Fragment. Must show a confirmation dialog on the main thread and
   * return the user's choice. Defaults to deny when unset.
   */
  var confirmHandler: (suspend (title: String, detail: String) -> Boolean)? = null

  private var currentEngine: AgentEngine? = null
  private var currentJob: Job? = null

  init {
    AiAgent.init(application)
    permissions.confirmCallback = { title, detail ->
      confirmHandler?.invoke(title, detail) == true
    }
    _uiState.update { it.copy(activeProvider = resolveInitialProvider()) }
    refreshCapabilities()
  }

  /** Switches the active provider for this session (drives capability-gated UI). */
  fun setActiveProvider(plugin: AiProviderPlugin) {
    if (_uiState.value.isBusy) return
    _uiState.update { it.copy(activeProvider = plugin) }
    refreshCapabilities()
  }

  /**
   * Re-resolves the active provider's capabilities off the main thread.
   * Reading them touches EncryptedSharedPreferences (keystore), so this must
   * never run per-render — previously it ran on every streamed token.
   */
  fun refreshCapabilities() {
    val plugin = _uiState.value.activeProvider
    if (plugin == null) {
      _uiState.update { it.copy(capabilities = emptySet()) }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val caps = CapabilityUi.resolvedCaps(plugin)
      _uiState.update { it.copy(capabilities = caps) }
    }
  }

  /** Switches between AGENT (tools enabled) and CHAT (conversation only) modes. */
  fun setMode(mode: AgentMode) {
    if (_uiState.value.isBusy) return
    _uiState.update { it.copy(mode = mode) }
  }

  fun addPendingAttachment(attachment: Attachment) {
    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
  }

  fun removePendingAttachment(attachment: Attachment) {
    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - attachment) }
  }

  /** Validates capabilities/config, then runs the message through the agent engine. */
  fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
    val state = _uiState.value
    if (state.isBusy) return
    val trimmed = text.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return

    val plugin = state.activeProvider
    if (plugin == null) {
      addError(getApplication<Application>().getString(R.string.aiagent_no_providers))
      return
    }

    // Claim the busy flag synchronously on the caller thread (main), before
    // the coroutine starts: otherwise a second send can slip through the
    // isBusy check above while the first coroutine is still starting up,
    // spawning two engines for a single chat.
    _uiState.update { it.copy(isBusy = true) }

    currentJob = viewModelScope.launch {
      val app = getApplication<Application>()

      val config = AiAgent.configStore().getActiveConfig()
      if (config == null) {
        addError(
          app.getString(R.string.aiagent_no_provider_configured) + " " +
            app.getString(R.string.aiagent_open_settings)
        )
        _uiState.update { it.copy(isBusy = false) }
        return@launch
      }
      if (config.providerId != plugin.id) {
        addError(app.getString(R.string.aiagent_no_config_for_provider, plugin.displayName))
        _uiState.update { it.copy(isBusy = false) }
        return@launch
      }

      // Capability gate: never send an attachment the provider cannot handle.
      for (attachment in attachments) {
        val missing = CapabilityGate.missingCapability(plugin.resolvedCapabilities(config), attachment)
        if (missing != null) {
          addError(CapabilityGate.unavailableMessage(plugin.displayName, missing))
          _uiState.update { it.copy(isBusy = false) }
          return@launch
        }
      }

      val engine = AiAgent.newEngine(plugin, config, projectRoot, state.mode,
        permissions = permissions)
      currentEngine = engine

      _uiState.update {
        it.copy(
          pendingAttachments = emptyList(),
          messages = it.messages + ChatListItem.User(trimmed, attachments)
        )
      }

      try {
        engine.run(ChatMessage(Role.USER, trimmed, attachments)).collect { event ->
          handleEvent(event)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        appendItem(
          ChatListItem.Error(e.message ?: app.getString(R.string.aiagent_error_generic))
        )
        finalizeStreaming()
      } finally {
        currentEngine = null
        _uiState.update { it.copy(isBusy = false) }
      }
    }
  }

  /** Cancels the in-flight engine run. */
  fun cancel() {
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

  private fun resolveInitialProvider(): AiProviderPlugin? {
    val config = AiAgent.configStore().getActiveConfig()
    val registry = AiAgent.registry()
    if (config != null) {
      registry.get(config.providerId)?.let { return it }
    }
    return registry.all().firstOrNull()
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
        if (event.summary.isNotBlank()) appendItem(ChatListItem.Status(event.summary))
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

  private fun markToolFinished(name: String, ok: Boolean) {
    _uiState.update { state ->
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
}

/** Factory that supplies the [Application] and optional project path to [AiChatViewModel]. */
class AiChatViewModelFactory(
  private val application: Application,
  private val projectPath: String?
) : ViewModelProvider.Factory {

  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AiChatViewModel::class.java)) {
      return AiChatViewModel(application, projectPath) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}
