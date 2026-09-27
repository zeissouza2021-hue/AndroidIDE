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

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.AttachmentType
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import dev.mutwakil.androidide.aiagent.voice.VoiceInputController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Multimodal chat UI for the AI development agent.
 *
 * The input bar adapts to the active provider's [capabilities][dev.mutwakil.androidide.aiagent.model.Capability]:
 * the image/file/mic buttons are only visible when the provider declares
 * `IMAGE_INPUT`/`VISION`, `FILE_INPUT`/`PDF_INPUT` and `VOICE_INPUT` respectively.
 * Attachments that slip through (or a missing provider configuration) are
 * rejected with a clear error via `CapabilityGate`.
 */
class AiChatFragment : Fragment() {

  private lateinit var viewModel: AiChatViewModel
  private lateinit var voiceController: VoiceInputController

  private lateinit var messageList: RecyclerView
  private lateinit var messageAdapter: ChatMessageAdapter
  private lateinit var emptyState: TextView
  private lateinit var statusBanner: MaterialCardView
  private lateinit var statusText: TextView
  private lateinit var stopButton: MaterialButton
  private lateinit var pendingList: RecyclerView
  private lateinit var pendingAdapter: PendingAttachmentAdapter
  private lateinit var modeToggle: MaterialButtonToggleGroup
  private lateinit var capabilitySummary: TextView
  private lateinit var attachImageButton: MaterialButton
  private lateinit var attachFileButton: MaterialButton
  private lateinit var micButton: MaterialButton
  private lateinit var messageInput: TextInputEditText
  private lateinit var sendButton: MaterialButton

  private val pickImageLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      uri?.let { onContentPicked(it) }
    }

  private val pickFileLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      uri?.let { onContentPicked(it) }
    }

  private val audioPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startVoiceInput()
      else showMessage(getString(R.string.aiagent_voice_permission_required))
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val app = requireActivity().application
    val projectPath = arguments?.getString(ARG_PROJECT_PATH)
    viewModel = ViewModelProvider(
      this,
      AiChatViewModelFactory(app, projectPath)
    )[AiChatViewModel::class.java]
    voiceController = VoiceInputController(app)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View = inflater.inflate(R.layout.fragment_ai_chat, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    bindViews(view)
    setupMessageList()
    setupPendingAttachments()
    setupInputBar()
    setupVoiceAndAttachments()

    // Route destructive tool confirmations through a Material dialog.
    viewModel.confirmHandler = { title, detail -> showConfirmDialog(title, detail) }

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { render(it) }
      }
    }
  }

  override fun onDestroyView() {
    voiceController.stopListening()
    viewModel.confirmHandler = null
    super.onDestroyView()
  }

  /** Called by the host Activity when the user picks another provider. */
  fun setActiveProvider(plugin: AiProviderPlugin) {
    if (!::viewModel.isInitialized) return
    viewModel.setActiveProvider(plugin)
    showMessage(getString(R.string.aiagent_provider_switched, plugin.displayName))
    (activity as? AiChatActivity)?.refreshProviderMenu()
  }

  fun currentProviderId(): String? =
    if (::viewModel.isInitialized) viewModel.uiState.value.activeProvider?.id else null

  // ---------------------------------------------------------------------------
  // View setup
  // ---------------------------------------------------------------------------

  private fun bindViews(view: View) {
    messageList = view.findViewById(R.id.message_list)
    emptyState = view.findViewById(R.id.empty_state)
    statusBanner = view.findViewById(R.id.status_banner)
    statusText = view.findViewById(R.id.status_text)
    stopButton = view.findViewById(R.id.stop_button)
    pendingList = view.findViewById(R.id.pending_attachments_list)
    modeToggle = view.findViewById(R.id.mode_toggle)
    capabilitySummary = view.findViewById(R.id.capability_summary)
    attachImageButton = view.findViewById(R.id.attach_image_button)
    attachFileButton = view.findViewById(R.id.attach_file_button)
    micButton = view.findViewById(R.id.mic_button)
    messageInput = view.findViewById(R.id.message_input)
    sendButton = view.findViewById(R.id.send_button)
  }

  private fun setupMessageList() {
    messageAdapter = ChatMessageAdapter()
    messageList.adapter = messageAdapter
    messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
      override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        messageList.smoothScrollToPosition(messageAdapter.itemCount - 1)
      }
    })
  }

  private fun setupPendingAttachments() {
    pendingAdapter = PendingAttachmentAdapter(viewModel::removePendingAttachment)
    pendingList.adapter = pendingAdapter
  }

  private fun setupInputBar() {
    sendButton.setOnClickListener { send() }
    messageInput.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        send()
        true
      } else {
        false
      }
    }
    stopButton.setOnClickListener { viewModel.cancel() }
    modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      val mode = if (checkedId == R.id.mode_button_chat) AgentMode.CHAT else AgentMode.AGENT
      viewModel.setMode(mode)
      val message = if (mode == AgentMode.CHAT) {
        getString(R.string.aiagent_mode_changed_chat)
      } else {
        getString(R.string.aiagent_mode_changed_agent)
      }
      viewModel.postStatus(message)
    }
  }

  private fun setupVoiceAndAttachments() {
    attachImageButton.setOnClickListener { pickImageLauncher.launch("image/*") }
    attachFileButton.setOnClickListener { pickFileLauncher.launch("*/*") }
    micButton.setOnClickListener {
      if (voiceController.needsPermission()) {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      } else {
        startVoiceInput()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private fun render(state: ChatUiState) {
    messageAdapter.submitList(state.messages)
    emptyState.isVisible = state.messages.isEmpty()
    statusBanner.isVisible = state.isBusy
    if (state.isBusy) {
      val lastStatus = state.messages.lastOrNull { it is ChatListItem.Status } as? ChatListItem.Status
      statusText.text = lastStatus?.text ?: getString(R.string.aiagent_working)
    }

    pendingAdapter.submitList(state.pendingAttachments)
    pendingList.isVisible = state.pendingAttachments.isNotEmpty()

    // Capability-gated input bar: hide what the provider cannot do.
    val caps = state.activeProvider?.let { CapabilityUi.resolvedCaps(it) } ?: emptySet()
    attachImageButton.isVisible = CapabilityUi.supportsImageInput(caps)
    attachFileButton.isVisible = CapabilityUi.supportsFileInput(caps)
    micButton.isVisible = CapabilityUi.supportsVoiceInput(caps)
    capabilitySummary.text = state.activeProvider?.let { plugin ->
      "${plugin.displayName} \u00b7 ${CapabilityUi.summary(requireContext(), caps)}"
    } ?: getString(R.string.aiagent_no_providers)

    // Keep the toggle in sync with the state (e.g. after rotation).
    val checkedId = if (state.mode == AgentMode.CHAT) R.id.mode_button_chat else R.id.mode_button_agent
    if (modeToggle.checkedButtonId != checkedId) modeToggle.check(checkedId)

    sendButton.isEnabled = !state.isBusy
  }

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  private fun send() {
    val state = viewModel.uiState.value
    viewModel.sendMessage(messageInput.text?.toString().orEmpty(), state.pendingAttachments)
    messageInput.text?.clear()
  }

  private fun onContentPicked(uri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
      val attachment = try {
        uriToAttachment(uri)
      } catch (e: Exception) {
        null
      }
      withContext(Dispatchers.Main) {
        if (attachment != null) {
          viewModel.addPendingAttachment(attachment)
        } else {
          showMessage(getString(R.string.aiagent_error_generic))
        }
      }
    }
  }

  /** Copies the picked content into the app cache dir and wraps it as an [Attachment]. */
  private fun uriToAttachment(uri: Uri): Attachment? {
    val context = requireContext()
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: guessMimeType(uri) ?: "application/octet-stream"
    val displayName = queryDisplayName(uri) ?: "attachment"
    val dir = File(context.cacheDir, "aiagent_attachments").apply { mkdirs() }
    val dest = File(dir, "${System.currentTimeMillis()}_$displayName")
    resolver.openInputStream(uri)?.use { input ->
      dest.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    return Attachment(
      type = attachmentTypeFor(mimeType, displayName),
      file = dest,
      mimeType = mimeType,
      displayName = displayName
    )
  }

  private fun queryDisplayName(uri: Uri): String? {
    val context = requireContext()
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (index != -1 && cursor.moveToFirst()) {
        return cursor.getString(index)
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
  }

  private fun guessMimeType(uri: Uri): String? {
    return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "pdf" -> "application/pdf"
      "mp4" -> "video/mp4"
      "mp3" -> "audio/mpeg"
      "wav" -> "audio/wav"
      "txt", "md" -> "text/plain"
      "json" -> "application/json"
      else -> null
    }
  }

  private fun attachmentTypeFor(mimeType: String, displayName: String): AttachmentType {
    val lower = mimeType.lowercase()
    return when {
      lower.startsWith("image/") -> AttachmentType.IMAGE
      lower == "application/pdf" -> AttachmentType.PDF
      lower.startsWith("video/") -> AttachmentType.VIDEO
      lower.startsWith("audio/") -> AttachmentType.AUDIO
      lower.startsWith("text/") || CODE_EXTENSIONS.any { displayName.endsWith(".$it", ignoreCase = true) } ->
        AttachmentType.CODE
      else -> AttachmentType.DOCUMENT
    }
  }

  private fun startVoiceInput() {
    if (!voiceController.isAvailable(requireContext())) {
      showMessage(getString(R.string.aiagent_voice_unavailable))
      return
    }
    showMessage(getString(R.string.aiagent_voice_listening))
    voiceController.startListening(
      onResult = { text ->
        val current = messageInput.text?.toString().orEmpty()
        val separator = if (current.isBlank()) "" else " "
        messageInput.setText("$current$separator$text")
        messageInput.setSelection(messageInput.text?.length ?: 0)
      },
      onError = { message -> showMessage(message) }
    )
  }

  private suspend fun showConfirmDialog(title: String, detail: String): Boolean {
    return try {
      withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton(R.string.aiagent_allow) { _, _ ->
              if (!cont.isCompleted) cont.resume(true)
            }
            .setNegativeButton(R.string.aiagent_deny) { _, _ ->
              if (!cont.isCompleted) cont.resume(false)
            }
            .setOnCancelListener {
              if (!cont.isCompleted) cont.resume(false)
            }
            .show()
        }
      }
    } catch (e: Exception) {
      false // Fragment detached etc.: deny by default.
    }
  }

  private fun showMessage(message: String) {
    view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
  }

  companion object {
    private const val ARG_PROJECT_PATH = "project_path"

    private val CODE_EXTENSIONS = setOf(
      "kt", "kts", "java", "xml", "gradle", "py", "js", "ts",
      "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "php", "rb", "html", "css"
    )

    fun newInstance(projectPath: String?): AiChatFragment =
      AiChatFragment().apply {
        arguments = Bundle().apply { putString(ARG_PROJECT_PATH, projectPath) }
      }
  }
}
