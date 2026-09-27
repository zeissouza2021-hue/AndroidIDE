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
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * Janela flutuante do AI Chat (v3): comporta-se como uma janela de IDE
 * desktop sobre o editor — arrastável pela barra de título, redimensionável
 * pela alça do canto, minimizável, maximizável/restaurável e fechável.
 *
 * A janela é não-modal: o editor continua utilizável atrás dela. Cada
 * instância hospeda seu próprio [AiChatFragment] (ViewModel com escopo do
 * fragment), então várias janelas têm estado e conversa independentes.
 *
 * Trocar de modelo no seletor preserva a conversa atual.
 */
class AiChatV2PanelDialog : DialogFragment() {

    private var fragment: AiChatFragment? = null
    private lateinit var titleBar: View
    private lateinit var fragmentContainer: View
    private lateinit var resizeHandle: View
    private lateinit var maximizeButton: MaterialButton

    // ---- Geometria da janela (px) ----
    private var winX = 0
    private var winY = 0
    private var winW = 0
    private var winH = 0
    private var isMaximized = false
    private var isMinimized = false
    private var savedRect: Rect? = null
    private var savedHeight = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            // A janela flutua sobre o editor sem bloquear a interação com ele.
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_ai_chat_v2_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleBar = view.findViewById(R.id.ai_chat_v2_title_bar)
        fragmentContainer = view.findViewById(R.id.ai_chat_v2_fragment_container)
        resizeHandle = view.findViewById(R.id.ai_chat_v2_resize_handle)
        maximizeButton = view.findViewById(R.id.ai_chat_v2_maximize_button)

        restoreGeometry(savedInstanceState)
        styleFloatingWindow()
        setupDrag()
        setupResize()
        setupWindowButtons(view)

        val modelSelector = view.findViewById<AutoCompleteTextView>(R.id.ai_chat_v2_model_selector)

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
        applyMinimizedUi()
        if (isMinimized) {
            // Recalcula a altura com a barra de título já medida.
            titleBar.post { applyGeometry() }
        }
    }

    override fun onStart() {
        super.onStart()
        registerWindow(this)
        // Animação de entrada: fade + leve escala (janela surgindo).
        dialog?.window?.decorView?.let { decor ->
            decor.scaleX = 0.96f
            decor.scaleY = 0.96f
            decor.alpha = 0f
            decor.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        unregisterWindow(this)
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        unregisterWindow(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_X, winX)
        outState.putInt(KEY_Y, winY)
        outState.putInt(KEY_W, winW)
        outState.putInt(KEY_H, winH)
        outState.putBoolean(KEY_MAX, isMaximized)
        outState.putBoolean(KEY_MIN, isMinimized)
        outState.putInt(KEY_SAVED_H, savedHeight)
        savedRect?.let { outState.putParcelable(KEY_RECT, it) }
    }

    // ------------------------------------------------------------------
    // Geometria
    // ------------------------------------------------------------------

    private fun restoreGeometry(saved: Bundle?) {
        val dm = resources.displayMetrics
        if (saved != null) {
            winX = saved.getInt(KEY_X)
            winY = saved.getInt(KEY_Y)
            winW = saved.getInt(KEY_W)
            winH = saved.getInt(KEY_H)
            isMaximized = saved.getBoolean(KEY_MAX)
            isMinimized = saved.getBoolean(KEY_MIN)
            savedHeight = saved.getInt(KEY_SAVED_H)
            @Suppress("DEPRECATION")
            savedRect = saved.getParcelable(KEY_RECT)
        } else {
            // Tamanho/posição inicial: painel generoso à direita, centralizado
            // verticalmente, com margem da borda da tela.
            val density = dm.density
            winW = minOf((dm.widthPixels * 0.85f).toInt(), (560 * density).toInt())
            winH = (dm.heightPixels * 0.68f).toInt()
                .coerceIn((360 * density).toInt(), dm.heightPixels)
            winX = dm.widthPixels - winW - (12 * density).toInt()
            winY = ((dm.heightPixels - winH) / 2).coerceAtLeast(0)
        }
    }

    /** Janela flutuante livre: posição/tamanho explícitos, sem dim, não-modal. */
    private fun styleFloatingWindow() {
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyGeometry()
    }

    private fun applyGeometry() {
        val window = dialog?.window ?: return
        val params = window.attributes
        params.gravity = Gravity.TOP or Gravity.START
        params.x = winX
        params.y = winY
        params.width = winW
        // Minimizada: altura explícita = só a barra de título (WRAP_CONTENT
        // não encolhe porque o conteúdo interno é match_parent).
        params.height = if (isMinimized) minimizedHeightPx() else winH
        window.attributes = params
    }

    /** Altura da janela minimizada: barra de título + divisor + paddings. */
    private fun minimizedHeightPx(): Int {
        val d = resources.displayMetrics.density
        val bar = titleBar.height.takeIf { it > 0 } ?: (104 * d).toInt()
        return bar + (13 * d).toInt()
    }

    private fun moveTo(x: Int, y: Int) {
        val dm = resources.displayMetrics
        val density = dm.density
        val minVisible = (72 * density).toInt()
        val titleH = if (titleBar.height > 0) titleBar.height else (96 * density).toInt()
        winX = x.coerceIn(-(winW - minVisible), dm.widthPixels - minVisible)
        winY = y.coerceIn(0, (dm.heightPixels - titleH).coerceAtLeast(0))
        if (isMaximized) {
            // Arrastar uma janela maximizada a restaura (comportamento desktop).
            isMaximized = false
            savedRect?.let { r ->
                winW = r.width()
                winH = r.height()
            }
            updateMaximizeIcon()
        }
        applyGeometry()
    }

    private fun minW(): Int = (280 * resources.displayMetrics.density).toInt()
    private fun minH(): Int = (360 * resources.displayMetrics.density).toInt()
    private fun maxW(): Int = resources.displayMetrics.widthPixels - (24 * resources.displayMetrics.density).toInt()
    private fun maxH(): Int = resources.displayMetrics.heightPixels - (24 * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Arrastar pela barra de título
    // ------------------------------------------------------------------

    private fun setupDrag() {
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = winX
                        startY = winY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        moveTo(
                            (startX + event.rawX - startRawX).toInt(),
                            (startY + event.rawY - startRawY).toInt()
                        )
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    // ------------------------------------------------------------------
    // Redimensionar pela alça do canto
    // ------------------------------------------------------------------

    private fun setupResize() {
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startW = 0
            private var startH = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startW = winW
                        startH = winH
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        winW = (startW + event.rawX - startRawX).toInt()
                            .coerceIn(minW(), maxW())
                        winH = (startH + event.rawY - startRawY).toInt()
                            .coerceIn(minH(), maxH())
                        if (isMaximized) {
                            isMaximized = false
                            updateMaximizeIcon()
                        }
                        applyGeometry()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    // ------------------------------------------------------------------
    // Botões da janela
    // ------------------------------------------------------------------

    private fun setupWindowButtons(view: View) {
        view.findViewById<MaterialButton>(R.id.ai_chat_v2_new_window_button)
            .setOnClickListener {
                val args = requireArguments()
                open(
                    parentFragmentManager,
                    args.getString(ARG_PROJECT_PATH) ?: "",
                    args.getString(ARG_FILE_PATH),
                    args.getString(ARG_SELECTION)
                )
            }
        view.findViewById<MaterialButton>(R.id.ai_chat_v2_minimize_button)
            .setOnClickListener { toggleMinimize() }
        maximizeButton.setOnClickListener { toggleMaximize() }
        view.findViewById<MaterialButton>(R.id.ai_chat_v2_close_button)
            .setOnClickListener { dismiss() }
        updateMaximizeIcon()
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        if (isMinimized) {
            savedHeight = winH
        } else if (savedHeight > 0) {
            winH = savedHeight.coerceIn(minH(), maxH())
        }
        applyMinimizedUi()
        applyGeometry()
    }

    private fun applyMinimizedUi() {
        val gone = if (isMinimized) View.GONE else View.VISIBLE
        fragmentContainer.visibility = gone
        resizeHandle.visibility = gone
    }

    private fun toggleMaximize() {
        val dm = resources.displayMetrics
        val margin = (8 * dm.density).toInt()
        if (isMaximized) {
            savedRect?.let {
                winX = it.left
                winY = it.top
                winW = it.width()
                winH = it.height()
            }
            isMaximized = false
        } else {
            if (isMinimized) toggleMinimize()
            savedRect = Rect(winX, winY, winX + winW, winY + winH)
            winX = margin
            winY = margin
            winW = dm.widthPixels - 2 * margin
            winH = dm.heightPixels - 2 * margin
            isMaximized = true
        }
        updateMaximizeIcon()
        applyGeometry()
    }

    private fun updateMaximizeIcon() {
        maximizeButton.setIconResource(
            if (isMaximized) R.drawable.ic_ai_chat_v2_restore
            else R.drawable.ic_ai_chat_v2_maximize
        )
        maximizeButton.contentDescription = getString(
            if (isMaximized) R.string.ai_chat_v2_restore else R.string.ai_chat_v2_maximize
        )
    }

    /** Injeta contexto do editor (arquivo/seleção) na conversa desta janela. */
    fun injectEditorContext(filePath: String?, selection: String?) {
        fragment?.injectEditorContext(filePath, selection)
    }

    // ------------------------------------------------------------------
    // Seletor de modelo (troca preservando a conversa)
    // ------------------------------------------------------------------

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

        private const val KEY_X = "win_x"
        private const val KEY_Y = "win_y"
        private const val KEY_W = "win_w"
        private const val KEY_H = "win_h"
        private const val KEY_MAX = "win_max"
        private const val KEY_MIN = "win_min"
        private const val KEY_SAVED_H = "win_saved_h"
        private const val KEY_RECT = "win_rect"

        private const val TAG_PANEL = "ai_chat_v2_panel"

        /** Janelas abertas (referências fracas); cada uma tem estado próprio. */
        private val openWindows = mutableListOf<WeakReference<AiChatV2PanelDialog>>()
        private var seq = 0

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

        /** Abre sempre uma nova janela (estado independente). */
        fun open(
            fragmentManager: FragmentManager,
            projectPath: String,
            initialFilePath: String? = null,
            initialSelection: String? = null
        ) {
            newInstance(projectPath, initialFilePath, initialSelection)
                .show(fragmentManager, "${TAG_PANEL}_${++seq}_${SystemClock.uptimeMillis()}")
        }

        /**
         * Abre a janela se não houver nenhuma; se já houver, injeta o
         * contexto do editor (arquivo/seleção) na mais recente em vez de
         * abrir outra. Chamar a partir da activity do editor.
         */
        fun openOrFocus(
            fragmentManager: FragmentManager,
            projectPath: String,
            initialFilePath: String? = null,
            initialSelection: String? = null
        ) {
            val top = latestWindow()
            if (top != null) {
                top.injectEditorContext(initialFilePath, initialSelection)
            } else {
                open(fragmentManager, projectPath, initialFilePath, initialSelection)
            }
        }

        /** Fecha todas as janelas abertas. */
        fun closeAll() {
            openWindows.mapNotNull { it.get() }.forEach { runCatching { it.dismiss() } }
        }

        private fun latestWindow(): AiChatV2PanelDialog? {
            pruneWindows()
            return openWindows.mapNotNull { it.get() }
                .lastOrNull { it.isAdded && it.dialog?.isShowing == true }
        }

        private fun registerWindow(d: AiChatV2PanelDialog) {
            pruneWindows()
            if (openWindows.none { it.get() === d }) {
                openWindows.add(WeakReference(d))
            }
        }

        private fun unregisterWindow(d: AiChatV2PanelDialog) {
            openWindows.removeAll { it.get() === d || it.get() == null }
        }

        private fun pruneWindows() {
            openWindows.removeAll { it.get() == null }
        }
    }
}
