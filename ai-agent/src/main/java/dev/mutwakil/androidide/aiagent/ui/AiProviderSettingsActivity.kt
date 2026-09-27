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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioButton
import android.widget.TextView
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import kotlinx.coroutines.launch

/**
 * Settings screen for the AI providers registered in [AiAgent.registry].
 *
 * Each row shows the provider name, its honestly-declared [Capability] set as
 * chips, the configured model (or a "not configured" hint) and a radio button
 * marking the active provider. Tapping a row opens the configuration dialog:
 * endpoint (OpenAI-compatible only), model (free text with suggestions from
 * [AiProviderPlugin.defaultModels]), API key (password field, stored encrypted
 * via the [dev.mutwakil.androidide.aiagent.store.ProviderConfigStore]), a
 * "Test connection" button and save.
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
      onSelectActive = { plugin ->
        AiAgent.configStore().setActiveProviderId(plugin.id)
        adapter.notifyDataSetChanged()
      }
    )
    findViewById<RecyclerView>(R.id.aiagent_provider_list).apply {
      layoutManager = LinearLayoutManager(this@AiProviderSettingsActivity)
      adapter = this@AiProviderSettingsActivity.adapter
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    val plugins = AiAgent.registry().all()
    val store = AiAgent.configStore()
    if (store.getActiveProviderId() == null && plugins.isNotEmpty()) {
      // Default to the first registered provider so the chat has something to use.
      store.setActiveProviderId(plugins.first().id)
    }
    adapter.submit(plugins)
    emptyView.isVisible = plugins.isEmpty()
  }

  private fun showConfigDialog(plugin: AiProviderPlugin) {
    val store = AiAgent.configStore()
    val current = store.getProviderConfig(plugin.id)
    val view = LayoutInflater.from(this).inflate(R.layout.aiagent_dialog_provider_config, null)

    val tilEndpoint = view.findViewById<TextInputLayout>(R.id.aiagent_til_endpoint)
    val etEndpoint = view.findViewById<TextInputEditText>(R.id.aiagent_et_endpoint)
    val actvModel = view.findViewById<AutoCompleteTextView>(R.id.aiagent_actv_model)
    val etApiKey = view.findViewById<TextInputEditText>(R.id.aiagent_et_api_key)
    val btnTest = view.findViewById<MaterialButton>(R.id.aiagent_btn_test)
    val tvResult = view.findViewById<TextView>(R.id.aiagent_tv_test_result)

    val showEndpoint = plugin.id == OPENAI_COMPATIBLE_PROVIDER_ID
    tilEndpoint.isVisible = showEndpoint
    etEndpoint.setText(current?.endpoint.orEmpty())
    actvModel.setText(current?.model.orEmpty())
    etApiKey.setText(current?.apiKey.orEmpty())
    if (plugin.defaultModels.isNotEmpty()) {
      actvModel.setAdapter(
        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, plugin.defaultModels)
      )
    }

    fun collectConfig(): ProviderConfig = ProviderConfig(
      providerId = plugin.id,
      displayName = plugin.displayName,
      apiKey = etApiKey.text?.toString()?.takeIf { it.isNotBlank() },
      endpoint = if (showEndpoint) {
        etEndpoint.text?.toString()?.takeIf { it.isNotBlank() }
      } else {
        current?.endpoint
      },
      model = actvModel.text?.toString().orEmpty()
    )

    btnTest.setOnClickListener {
      btnTest.isEnabled = false
      tvResult.isVisible = true
      tvResult.setText(R.string.aiagent_config_testing)
      val config = collectConfig()
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

    MaterialAlertDialogBuilder(this)
      .setTitle(plugin.displayName)
      .setView(view)
      .setPositiveButton(R.string.aiagent_config_save) { _, _ ->
        store.saveProviderConfig(collectConfig())
        adapter.notifyDataSetChanged()
      }
      .setNegativeButton(R.string.aiagent_config_cancel, null)
      .show()
  }

  private inner class ProviderAdapter(
    private val onEdit: (AiProviderPlugin) -> Unit,
    private val onSelectActive: (AiProviderPlugin) -> Unit
  ) : RecyclerView.Adapter<ProviderAdapter.Holder>() {

    private var plugins: List<AiProviderPlugin> = emptyList()

    fun submit(plugins: List<AiProviderPlugin>) {
      this.plugins = plugins
      notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
      val view = LayoutInflater.from(parent.context)
        .inflate(R.layout.aiagent_item_provider, parent, false)
      return Holder(view)
    }

    override fun getItemCount(): Int = plugins.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
      holder.bind(plugins[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
      private val radio: RadioButton = view.findViewById(R.id.aiagent_radio_active)
      private val name: TextView = view.findViewById(R.id.aiagent_provider_name)
      private val model: TextView = view.findViewById(R.id.aiagent_provider_model)
      private val chips: ChipGroup = view.findViewById(R.id.aiagent_capability_chips)

      fun bind(plugin: AiProviderPlugin) {
        val store = AiAgent.configStore()
        val config = store.getProviderConfig(plugin.id)
        name.text = plugin.displayName
        model.text = config?.model?.takeIf { it.isNotBlank() }
          ?: itemView.context.getString(R.string.aiagent_provider_not_configured)
        radio.isChecked = store.getActiveProviderId() == plugin.id
        radio.setOnClickListener { onSelectActive(plugin) }
        chips.removeAllViews()
        val caps = config?.let { plugin.resolvedCapabilities(it) } ?: plugin.capabilities
        caps
          .map { itemView.context.capabilityLabel(it) }
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
        itemView.setOnClickListener { onEdit(plugin) }
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
