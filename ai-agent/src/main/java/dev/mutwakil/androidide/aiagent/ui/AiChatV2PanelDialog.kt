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

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import kotlinx.coroutines.launch

/**
 * Chat flutuante v2: painel lateral que desliza sobre o editor (item 1).
 *
 * Reutiliza o [AiChatFragment] (filho deste dialog) e adiciona o cabeçalho do
 * painel: título, seletor de modelo (item 3) e botão fechar. As abas
 * Perguntar/Agente ficam no topo do próprio fragment.
 *
 * O dialog é criado com o contexto da activity, então o tema do app (cores,
 * tipografia) se aplica automaticamente ao painel.
 */
class AiChatV2PanelDialog : DialogFragment() {

    private var fragment: AiChatFragment? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Sem tema explícito: o Dialog resolve R.attr.dialogTheme do tema da
        // activity (Theme.AndroidIDE), herdando as cores do app.
        return Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_ai_chat_v2_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stylePanelWindow()

        val modelSelector = view.findViewById<AutoCompleteTextView>(R.id.ai_chat_v2_model_selector)
        view.findViewById<MaterialButton>(R.id.ai_chat_v2_close_button)
            .setOnClickListener { dismiss() }

        val args = requireArguments()
        val projectPath = args.getString(ARG_PROJECT_PATH) ?: ""
        val chatFragment = childFragmentManager.findFragmentByTag(TAG_CHAT) as? AiChatFragment
            ?: AiChatFragment.newInstance(
                projectPath,
                args.getString(ARG_FILE_PATH),
                args.getString(ARG_SELECTION)
            ).also { frag ->
                childFragmentManager.beginTransaction()
                    .replace(R.id.ai_chat_v2_fragment_container, frag, TAG_CHAT)
                    .commitNow()
            }
        fragment = chatFragment

        setupModelSelector(modelSelector, chatFragment)
    }

    override fun onStart() {
        super.onStart()
        // Animação de entrada: desliza da direita.
        dialog?.window?.decorView?.let { decor ->
            val width = decor.width.toFloat()
            decor.translationX = width
            decor.animate()
                .translationX(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Janela lateral direita: altura total, largura 90% (máx. 480dp), fundo transparente. */
    private fun stylePanelWindow() {
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val params = window.attributes
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.width = panelWidthPx()
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        // Escurece o editor atrás do painel (dialog modal padrão já faz isso
        // com dimAmount do tema; reforçamos um leve dim).
        params.dimAmount = 0.25f
        window.attributes = params
    }

    private fun panelWidthPx(): Int {
        val metrics = resources.displayMetrics
        val target = (metrics.widthPixels * 0.9f).toInt()
        val max = (480 * metrics.density).toInt()
        return minOf(target, max)
    }

    private fun setupModelSelector(
        selector: AutoCompleteTextView,
        chatFragment: AiChatFragment
    ) {
        AiChatV2Providers.ensureValidState()
        val entries = AiChatV2Providers.listConfigs()
        if (entries.isEmpty()) {
            // v2.1: sem nenhuma API, o seletor vira um atalho para adicionar.
            selector.setText(
                getString(R.string.ai_chat_v2_model_add_api_hint), false
            )
            selector.setOnClickListener {
                startActivity(
                    Intent(requireContext(), AiProviderSettingsActivity::class.java)
                )
            }
            // Quando voltar da tela de providers com algo criado, monta o
            // seletor normal uma única vez e já seleciona a primeira entrada.
            var bound = false
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    val now = AiChatV2Providers.listConfigs()
                    if (now.isNotEmpty() && !bound) {
                        bound = true
                        selector.setOnClickListener(null)
                        bindSelectorEntries(selector, chatFragment, now)
                        if (chatFragment.viewModel.uiState.value.activeConfigId == null) {
                            chatFragment.setActiveConfig(now.first().configId)
                        }
                    }
                }
            }
            return
        }
        bindSelectorEntries(selector, chatFragment, entries)
    }

    private fun bindSelectorEntries(
        selector: AutoCompleteTextView,
        chatFragment: AiChatFragment,
        entries: List<ProviderConfig>
    ) {
        val options = entries.map { ModelOption(it) }
        selector.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)
        )
        selector.setOnItemClickListener { _, _, position, _ ->
            chatFragment.setActiveConfig(options[position].config.configId)
        }
        selector.setText(currentLabel(entries), false)

        // Mantém o seletor sincronizado (ex.: troca automática de fallback).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatFragment.viewModel.uiState.collect { state ->
                    val active = entries.firstOrNull { it.configId == state.activeConfigId }
                    val label = labelFor(active)
                    if (selector.text.toString() != label) {
                        selector.setText(label, false)
                    }
                }
            }
        }
    }

    private fun currentLabel(entries: List<ProviderConfig>): String {
        val activeId = runCatching { AiAgent.configStore().getActiveConfigId() }.getOrNull()
        return labelFor(entries.firstOrNull { it.configId == activeId })
    }

    private fun labelFor(config: ProviderConfig?): String =
        config?.let { "${it.displayName} • ${it.model.ifBlank { "—" }}" } ?: ""

    /** Opção do dropdown; o texto exibido vem do toString(). */
    private class ModelOption(val config: ProviderConfig) {
        override fun toString(): String =
            "${config.displayName} • ${config.model.ifBlank { "—" }}"
    }

    companion object {
        private const val TAG_CHAT = "ai_chat_v2_chat"
        private const val ARG_PROJECT_PATH = "project_path"
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_SELECTION = "selection"

        fun newInstance(
            projectPath: String,
            initialFilePath: String? = null,
            initialSelection: String? = null
        ): AiChatV2PanelDialog = AiChatV2PanelDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PROJECT_PATH, projectPath)
                putString(ARG_FILE_PATH, initialFilePath)
                putString(ARG_SELECTION, initialSelection)
            }
        }

        /**
         * Abre o painel se fechado, fecha se aberto (toggle).
         * Chamar a partir da activity do editor.
         */
        fun toggle(
            fragmentManager: FragmentManager,
            projectPath: String,
            initialFilePath: String? = null,
            initialSelection: String? = null
        ) {
            val existing = fragmentManager.findFragmentByTag(TAG_PANEL)
            if (existing is DialogFragment) {
                existing.dismiss()
            } else {
                newInstance(projectPath, initialFilePath, initialSelection)
                    .show(fragmentManager, TAG_PANEL)
            }
        }

        private const val TAG_PANEL = "ai_chat_v2_panel"
    }
}
