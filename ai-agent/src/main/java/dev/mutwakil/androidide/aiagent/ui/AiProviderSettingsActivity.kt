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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import kotlinx.coroutines.launch

/**
 * Settings screen for AI provider entries (chat v2, item 4).
 *
 * Lists every saved entry — the editable defaults (one per protocol plugin)
 * plus user-created ones — instead of the bare plugins. Each row shows the
 * entry name, model, protocol, capability chips (with overrides applied), a
 * radio button marking the active entry, a "use as fallback" switch and, for
 * custom entries, a delete button. Tapping a row opens the editor dialog;
 * the FAB creates a new custom entry (custom name, protocol, base URL,
 * model id, encrypted API key, fallback toggle, capability overrides).
 */
class AiProviderSettingsActivity : AppCompatActivity() {

  private lateinit var adapter: ProviderAdapter
  private lateinit var emptyView: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.aiagent_activity_provider_settings)

    findViewById<MaterialToolbar>(R.id.aiagent_toolbar).setNavigationOnClickListener {
      onBackPressedDispatcher.onBackPressed()
    }

    emptyView = findViewById(R.id.aiagent_empty_view)
    adapter = ProviderAdapter(
      onEdit = ::showConfigDialog,
      onSelectActive = { config ->
        AiAgent.configStore().setActiveConfigId(config.configId)
        adapter.notifyDataSetChanged()
      },
      onDelete = ::confirmDelete
    )
    findViewById<RecyclerView>(R.id.aiagent_provider_list).apply {
      layoutManager = LinearLayoutManager(this@AiProviderSettingsActivity)
      adapter = this@AiProviderSettingsActivity.adapter
    }
    findViewById<FloatingActionButton>(R.id.ai_chat_v2_add_provider_fab)
      .setOnClickListener { showConfigDialog(null) }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    AiChatV2Providers.ensureValidState()
    val configs = AiChatV2Providers.listConfigs()
    val store = AiAgent.configStore()
    if (store.getActiveConfigId() == null && configs.isNotEmpty()) {
      // Default to the first entry so the chat has something to use.
      store.setActiveConfigId(configs.first().configId)
    }
    adapter.submit(configs)
    emptyView.isVisible = configs.isEmpty()
  }

  private fun confirmDelete(config: ProviderConfig) {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.ai_chat_v2_provider_delete_title)
      .setMessage(getString(R.string.ai_chat_v2_provider_delete_confirm, config.displayName))
      .setPositiveButton(R.string.ai_chat_v2_provider_delete) { _, _ ->
        AiAgent.configStore().deleteProviderConfig(config.configId)
        Snackbar.make(
          findViewById(R.id.aiagent_provider_list),
          R.string.ai_chat_v2_provider_deleted,
          Snackbar.LENGTH_SHORT
        ).show()
        refresh()
      }
      .setNegativeButton(R.string.aiagent_config_cancel, null)
      .show()
  }

  /**
   * Entry editor dialog. `existing == null` creates a new custom entry
   * (protocol selectable); otherwise edits it (protocol fixed).
   */
  private fun showConfigDialog(existing: ProviderConfig?) {
    val registry = AiAgent.registry()
    val protocols = registry.all()
    if (protocols.isEmpty()) return

    val view = LayoutInflater.from(this)
      .inflate(R.layout.ai_chat_v2_dialog_provider_config, null)

    val etName = view.findViewById<TextInputEditText>(R.id.ai_chat_v2_et_name)
    val actvProtocol = view.findViewById<AutoCompleteTextView>(R.id.ai_chat_v2_actv_protocol)
    val tilEndpoint = view.findViewById<TextInputLayout>(R.id.ai_chat_v2_til_endpoint)
    val etEndpoint = view.findViewById<TextInputEditText>(R.id.ai_chat_v2_et_endpoint)
    val actvModel = view.findViewById<AutoCompleteTextView>(R.id.ai_chat_v2_actv_model)
    val etApiKey = view.findViewById<TextInputEditText>(R.id.ai_chat_v2_et_api_key)
    val switchFallback = view.findViewById<MaterialSwitch>(R.id.ai_chat_v2_switch_fallback)
    val tvCapsMode = view.findViewById<TextView>(R.id.ai_chat_v2_tv_caps_mode)
    val capsGroup = view.findViewById<ChipGroup>(R.id.ai_chat_v2_caps_chip_group)
    val btnCapsDefault = view.findViewById<MaterialButton>(R.id.ai_chat_v2_btn_caps_default)
    val btnTest = view.findViewById<MaterialButton>(R.id.ai_chat_v2_btn_test)
    val tvResult = view.findViewById<TextView>(R.id.ai_chat_v2_tv_test_result)

    // v2.1: seção "avançado" recolhível — o diálogo fica compacto e o botão
    // salvar nunca some da tela.
    val advancedHeader = view.findViewById<View>(R.id.ai_chat_v2_advanced_header)
    val advancedBody = view.findViewById<View>(R.id.ai_chat_v2_advanced_body)
    val advancedChevron = view.findViewById<ImageView>(R.id.ai_chat_v2_advanced_chevron)
    var advancedExpanded = false
    fun setAdvancedExpanded(expanded: Boolean) {
      advancedExpanded = expanded
      advancedBody.isVisible = expanded
      advancedChevron.animate().rotation(if (expanded) 180f else 0f)
        .setDuration(150).start()
    }
    advancedHeader.setOnClickListener { setAdvancedExpanded(!advancedExpanded) }

    val protocolNames = protocols.map { it.displayName }
    val protocolIds = protocols.map { it.id }
    var selectedProtocolId = existing?.providerId
      ?: protocolIds.firstOrNull { it == OPENAI_COMPATIBLE_PROVIDER_ID }
      ?: protocolIds.first()

    fun selectedPlugin(): AiProviderPlugin? = registry.get(selectedProtocolId)

    var capsOverride: Set<Capability>? = existing?.capabilityOverrides
    var capsTouched = false

    fun refreshCapsChips() {
      val plugin = selectedPlugin()
      val effective = capsOverride
        ?: plugin?.let { AiChatV2Capabilities.resolve(it, existing) }
        ?: emptySet()
      capsGroup.removeAllViews()
      // v2: agrupa por rótulo — várias Capability mapeiam para o mesmo chip
      // (ex.: IMAGE_INPUT e VISION viram "Vision"); sem isso o diálogo mostrava
      // chips duplicados ("Vision" 2x, "Files" 3x, "Tools" 2x).
      Capability.values()
        .groupBy { capabilityLabel(it) }
        .forEach { (_, capabilities) ->
          val representative = capabilities.first()
          capsGroup.addView(
            Chip(this).apply {
              text = capabilityLabel(representative)
              tag = capabilities.toSet()
              isCheckable = true
              isChecked = capabilities.any { it in effective }
              setOnCheckedChangeListener { _, _ -> capsTouched = true }
            }
          )
        }
      tvCapsMode.text = if (capsOverride == null) {
        getString(R.string.ai_chat_v2_provider_capabilities) + " — " +
          getString(R.string.ai_chat_v2_provider_caps_default)
      } else {
        getString(R.string.ai_chat_v2_provider_capabilities) + " — " +
          getString(R.string.ai_chat_v2_provider_caps_custom, capsOverride!!.size)
      }
    }

    fun refreshProtocolDependentViews() {
      val plugin = selectedPlugin()
      tilEndpoint.isVisible = selectedProtocolId == OPENAI_COMPATIBLE_PROVIDER_ID
      val models = plugin?.defaultModels.orEmpty()
      if (models.isNotEmpty()) {
        actvModel.setAdapter(
          ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models)
        )
      } else {
        actvModel.setAdapter(null)
      }
      refreshCapsChips()
    }

    // --- initial values ----------------------------------------------------
    etName.setText(existing?.displayName.orEmpty())
    actvProtocol.setText(
      protocols.firstOrNull { it.id == selectedProtocolId }?.displayName.orEmpty(),
      false
    )
    // Protocol is fixed for existing entries (defaults and customs alike).
    actvProtocol.isEnabled = existing == null
    if (existing == null) {
      actvProtocol.setAdapter(
        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, protocolNames)
      )
      actvProtocol.setOnItemClickListener { _, _, position, _ ->
        selectedProtocolId = protocolIds[position]
        refreshProtocolDependentViews()
      }
    }
    etEndpoint.setText(existing?.endpoint.orEmpty())
    actvModel.setText(existing?.model.orEmpty())
    etApiKey.setText(existing?.apiKey.orEmpty())
    switchFallback.isChecked = existing?.useAsFallback ?: true
    btnCapsDefault.setOnClickListener {
      capsOverride = null
      capsTouched = false
      refreshCapsChips()
    }
    refreshProtocolDependentViews()

    fun collectConfig(): ProviderConfig? {
      val plugin = selectedPlugin() ?: return null
      val name = etName.text?.toString()?.trim().orEmpty()
      val model = actvModel.text?.toString()?.trim().orEmpty()
      if (name.isBlank() || model.isBlank()) return null
      val checkedCaps = (0 until capsGroup.childCount)
        .map { capsGroup.getChildAt(it) as Chip }
        .filter { it.isChecked }
        .flatMap { chip ->
          @Suppress("UNCHECKED_CAST")
          (chip.tag as? Set<Capability>).orEmpty()
        }
        .toSet()
      return ProviderConfig(
        providerId = plugin.id,
        configId = existing?.configId ?: AiChatV2Providers.newCustomId(),
        displayName = name,
        apiKey = etApiKey.text?.toString()?.takeIf { it.isNotBlank() },
        endpoint = if (selectedProtocolId == OPENAI_COMPATIBLE_PROVIDER_ID) {
          etEndpoint.text?.toString()?.takeIf { it.isNotBlank() }
        } else {
          existing?.endpoint
        },
        model = model,
        useAsFallback = switchFallback.isChecked,
        capabilityOverrides = if (capsTouched) checkedCaps else capsOverride,
        isDefault = false
      )
    }

    btnTest.setOnClickListener {
      val plugin = selectedPlugin()
      val config = collectConfig()
      if (plugin == null || config == null) {
        tvResult.isVisible = true
        tvResult.setText(R.string.ai_chat_v2_provider_validation)
        return@setOnClickListener
      }
      btnTest.isEnabled = false
      tvResult.isVisible = true
      tvResult.setText(R.string.aiagent_config_testing)
      lifecycleScope.launch {
        try {
          val result = plugin.testConnection(config)
          tvResult.text = if (result.ok) {
            getString(R.string.aiagent_config_test_ok, result.message)
          } else {
            getString(R.string.aiagent_config_test_failed, result.message)
          }
        } catch (e: Exception) {
          tvResult.text = getString(
            R.string.aiagent_config_test_failed,
            e.message ?: e.javaClass.simpleName
          )
        } finally {
          btnTest.isEnabled = true
        }
      }
    }

    val dialog = MaterialAlertDialogBuilder(this)
      .setTitle(
        existing?.displayName ?: getString(R.string.ai_chat_v2_provider_new_title)
      )
      .setView(view)
      .setPositiveButton(R.string.aiagent_config_save, null)
      .setNegativeButton(R.string.aiagent_config_cancel, null)
      .create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener {
          val config = collectConfig()
          if (config == null) {
            // A mensagem de validação fica na seção avançado: abre para ela
            // nunca ficar escondida.
            setAdvancedExpanded(true)
            tvResult.isVisible = true
            tvResult.setText(R.string.ai_chat_v2_provider_validation)
            return@setOnClickListener
          }
          AiAgent.configStore().saveProviderConfig(config)
          Snackbar.make(
            findViewById(R.id.aiagent_provider_list),
            R.string.ai_chat_v2_provider_saved,
            Snackbar.LENGTH_SHORT
          ).show()
          dialog.dismiss()
          refresh()
        }
    }
    // v2.1: com o teclado aberto o botão salvar continuava escondido;
    // redimensiona o diálogo em vez de cobrir.
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
  }

  private inner class ProviderAdapter(
    private val onEdit: (ProviderConfig?) -> Unit,
    private val onSelectActive: (ProviderConfig) -> Unit,
    private val onDelete: (ProviderConfig) -> Unit
  ) : RecyclerView.Adapter<ProviderAdapter.Holder>() {

    private var configs: List<ProviderConfig> = emptyList()

    fun submit(configs: List<ProviderConfig>) {
      this.configs = configs
      notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
      val view = LayoutInflater.from(parent.context)
        .inflate(R.layout.aiagent_item_provider, parent, false)
      return Holder(view)
    }

    override fun getItemCount(): Int = configs.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
      holder.bind(configs[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
      private val radio: RadioButton = view.findViewById(R.id.aiagent_radio_active)
      private val name: TextView = view.findViewById(R.id.aiagent_provider_name)
      private val model: TextView = view.findViewById(R.id.aiagent_provider_model)
      private val protocol: TextView = view.findViewById(R.id.ai_chat_v2_provider_protocol)
      private val chips: ChipGroup = view.findViewById(R.id.aiagent_capability_chips)
      private val deleteButton: MaterialButton =
        view.findViewById(R.id.ai_chat_v2_delete_button)

      fun bind(config: ProviderConfig) {
        val store = AiAgent.configStore()
        val plugin = AiAgent.registry().get(config.providerId)
        name.text = config.displayName
        model.text = config.model.takeIf { it.isNotBlank() }
          ?: itemView.context.getString(R.string.aiagent_provider_not_configured)
        protocol.text = plugin?.displayName ?: config.providerId

        radio.setOnCheckedChangeListener(null)
        radio.isChecked = store.getActiveConfigId() == config.configId
        radio.setOnClickListener { onSelectActive(config) }

        chips.removeAllViews()
        val caps = plugin?.let { AiChatV2Capabilities.resolve(it, config) } ?: emptySet()
        caps.map { itemView.context.capabilityLabel(it) }
          .distinct()
          .forEach { label ->
            chips.addView(
              Chip(chips.context).apply {
                text = label
                isClickable = false
                isCheckable = false
              }
            )
          }

        deleteButton.setOnClickListener { onDelete(config) }

        itemView.setOnClickListener { onEdit(config) }
      }
    }
  }

  companion object {
    /** Provider id that accepts a custom endpoint URL (front-b: OpenAiCompatibleProvider). */
    private const val OPENAI_COMPATIBLE_PROVIDER_ID = "openai-compatible"
  }
}

/** Short display label for a [Capability], used on the settings chips. */
private fun Context.capabilityLabel(capability: Capability): String = getString(
  when (capability) {
    Capability.TEXT_INPUT -> R.string.aiagent_cap_text
    Capability.VOICE_INPUT -> R.string.aiagent_cap_voice
    Capability.IMAGE_INPUT, Capability.VISION -> R.string.aiagent_cap_vision
    Capability.FILE_INPUT, Capability.PDF_INPUT, Capability.VIDEO_INPUT -> R.string.aiagent_cap_files
    Capability.CODE_GENERATION -> R.string.aiagent_cap_code
    Capability.TOOL_CALLING, Capability.FUNCTION_CALLING -> R.string.aiagent_cap_tools
    Capability.STREAMING -> R.string.aiagent_cap_streaming
  }
)
