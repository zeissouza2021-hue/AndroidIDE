package dev.mutwakil.androidide.compose.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.mutwakil.androidide.compose.preview.databinding.ActivityComposePreviewBinding
import dev.mutwakil.androidide.compose.preview.deviceframe.DeviceFrameDialogs
import dev.mutwakil.androidide.compose.preview.deviceframe.DeviceFrameView
import dev.mutwakil.androidide.compose.preview.deviceframe.DeviceProfile
import dev.mutwakil.androidide.compose.preview.deviceframe.DeviceProfiles
import dev.mutwakil.androidide.compose.preview.runtime.ComposableRenderer
import dev.mutwakil.androidide.compose.preview.runtime.ComposeClassLoader
import dev.mutwakil.androidide.compose.preview.ui.BoundedComposeView
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.projects.builder.BuildService
import dev.mutwakil.androidide.resources.R as ResourcesR
import dev.mutwakil.androidide.tooling.api.messages.TaskExecutionMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import dev.mutwakil.androidide.ui.themes.IThemeManager

class ComposePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposePreviewBinding

    private val viewModel: ComposePreviewViewModel by viewModels()

    private var classLoader: ComposeClassLoader? = null
    private var singleRenderer: ComposableRenderer? = null
    private val multiRenderers = mutableMapOf<String, ComposableRenderer>()

    private var toggleMenuItem: android.view.MenuItem? = null
    private var selectorAdapter: ArrayAdapter<String>? = null

    private var deviceProfiles: List<DeviceProfile> = emptyList()
    private var deviceProfile: DeviceProfile? = null
    private var deviceFrameView: DeviceFrameView? = null
    private var framedComposeView: ComposeView? = null
    private var framedRenderer: ComposableRenderer? = null

    private val sourceCode: String by lazy {
        intent.getStringExtra(EXTRA_SOURCE_CODE) ?: ""
    }

    private val filePath: String by lazy {
        intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        IThemeManager.getInstance().applyTheme(this)
        
        setupClassLoader()
        setupToolbar()
        setupPreviewSelector()
        setupSinglePreview()
        setupDeviceProfiles()
        setupBuildButton()
        observeState()

        viewModel.initialize(this, filePath)

        if (sourceCode.isNotBlank()) {
            viewModel.onSourceChanged(sourceCode)
        }
    }

    private fun setupClassLoader() {
        classLoader = ComposeClassLoader(this)
    }

    private fun setupToolbar() {
        binding.toolbar.title =
            filePath.substringAfterLast('/').ifEmpty {
                getString(ResourcesR.string.title_compose_preview)
            }
        binding.toolbar.setNavigationOnClickListener { finish() }

        toggleMenuItem = binding.toolbar.menu.findItem(R.id.action_toggle_mode)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_toggle_mode -> {
                    viewModel.toggleDisplayMode()
                    true
                }
                R.id.action_device_frame -> {
                    showDeviceSelector()
                    true
                }
                R.id.action_device_frame_fold -> {
                    toggleFold()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDeviceProfiles() {
        deviceProfiles = DeviceProfiles.defaults(this)
    }

    /**
     * Seletor de perfis de aparelho ("emulador visual"). Sem moldura por
     * padrão, para o comportamento atual não regredir.
     */
    private fun showDeviceSelector() {
        DeviceFrameDialogs.showSelector(
            this,
            deviceProfiles,
            deviceProfile,
            noneLabel = getString(R.string.device_frame_none),
        ) { selected ->
            applyDeviceProfile(selected)
        }
    }

    private fun toggleFold() {
        val profile = deviceProfile?.takeIf { it.isFoldable } ?: return
        applyDeviceProfile(profile.copy(folded = !profile.folded))
    }

    /**
     * Aplica o perfil: cria um ComposeView com o Configuration do aparelho
     * (para LocalConfiguration/LocalDensity refletirem o dispositivo) dentro
     * da moldura. null = volta ao preview padrão, sem moldura.
     */
    private fun applyDeviceProfile(profile: DeviceProfile?) {
        deviceProfile = profile

        deviceFrameView?.let { binding.previewContainer.removeView(it) }
        deviceFrameView = null
        framedComposeView = null
        framedRenderer = null

        val loader = classLoader
        if (profile != null && loader != null) {
            val frame = DeviceFrameView(this)
            frame.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            val composeView =
                ComposeView(profile.wrap(this)).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                    )
                }
            frame.setProfile(profile, composeView)
            frame.onResizeFinished = { resized -> applyDeviceProfile(resized) }
            binding.previewContainer.addView(frame)
            deviceFrameView = frame
            framedComposeView = composeView
            framedRenderer = ComposableRenderer(composeView, loader)
        }

        binding.toolbar.menu.findItem(R.id.action_device_frame_fold)?.let { foldItem ->
            foldItem.isVisible = profile?.isFoldable == true
            foldItem.isChecked = profile?.folded == true
        }

        val state = viewModel.previewState.value
        updatePreviewVisibility(state)
        if (
            state is PreviewState.Ready &&
                viewModel.displayMode.value == DisplayMode.SINGLE
        ) {
            val selected = viewModel.selectedPreview.value
            if (selected != null) {
                renderSinglePreview(state, selected)
            }
        }
    }

    private fun updatePreviewVisibility(state: PreviewState) {
        val isReady = state is PreviewState.Ready
        val isAllMode = viewModel.displayMode.value == DisplayMode.ALL
        val framed = deviceProfile != null

        binding.previewScrollView.isVisible = isReady && isAllMode
        binding.singlePreviewView.isVisible = isReady && !isAllMode && !framed
        deviceFrameView?.isVisible = isReady && !isAllMode && framed
    }

    private fun setupPreviewSelector() {
        selectorAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                mutableListOf(),
            )
        selectorAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.previewSelector.adapter = selectorAdapter

        binding.previewSelector.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val selected = selectorAdapter?.getItem(position) ?: return
                    viewModel.selectPreview(selected)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupSinglePreview() {
        binding.singlePreviewView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        val loader = classLoader ?: return
        singleRenderer = ComposableRenderer(binding.singlePreviewView, loader)
    }

    private fun setupBuildButton() {
        binding.buildProjectButton.setOnClickListener {
            triggerBuild()
        }
        binding.errorBuildButton.setOnClickListener {
            triggerBuildFromError()
        }
    }

    private fun triggerBuild() {
        val state = viewModel.previewState.value
        if (state !is PreviewState.NeedsBuild) return

        executeBuild(state.modulePath, state.variantName)
    }

    private fun triggerBuildFromError() {
        val modulePath = viewModel.getModulePath()
        val variantName = viewModel.getVariantName()
        executeBuild(modulePath, variantName)
    }

    private fun executeBuild(modulePath: String, variantName: String) {
        val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
        if (buildService == null) {
            LOG.error("BuildService not available")
            return
        }

        if (buildService.isBuildInProgress) {
            LOG.warn("Build already in progress")
            return
        }

        viewModel.setBuildingState()

        val capitalizedVariant = variantName.replaceFirstChar { it.uppercaseChar() }
        val task =
            if (modulePath.isNotEmpty()) {
                "$modulePath:assemble$capitalizedVariant"
            } else {
                "assemble$capitalizedVariant"
            }
        LOG.info("Running build task: {}", task)

        val tasks = TaskExecutionMessage(listOf(task))

        buildService.executeTasks(tasks).whenComplete { result, error ->
            runOnUiThread {
                if (error != null || !result.isSuccessful) {
                    LOG.error("Build failed", error)
                    viewModel.setBuildFailed()
                } else {
                    LOG.info("Build completed, refreshing preview")
                    viewModel.refreshAfterBuild(this@ComposePreviewActivity)
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.previewState.collect { state ->
                    handlePreviewState(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.displayMode.collect { mode ->
                    updateDisplayMode(mode)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.availablePreviews.collect { previews ->
                    updatePreviewSelector(previews)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedPreview
                    .combine(viewModel.previewState) { selected, state -> Pair(selected, state) }
                    .collect { (selected, state) ->
                        if (
                            state is PreviewState.Ready &&
                                viewModel.displayMode.value == DisplayMode.SINGLE &&
                                selected != null
                        ) {
                            renderSinglePreview(state, selected)
                        }
                    }
            }
        }
    }

    private fun handlePreviewState(state: PreviewState) {
        binding.loadingOverlay.isVisible =
            state is PreviewState.Initializing ||
                state is PreviewState.Compiling ||
                state is PreviewState.Idle ||
                state is PreviewState.Building
        binding.errorContainer.isVisible = state is PreviewState.Error
        binding.emptyContainer.isVisible = state is PreviewState.Empty
        binding.needsBuildContainer.isVisible = state is PreviewState.NeedsBuild

        updatePreviewVisibility(state)

        when (state) {
            is PreviewState.Idle -> {
                binding.statusText.text = "Rendering..."
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Initializing -> {
                binding.statusText.text = "Initializing..."
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Compiling -> {
                binding.statusText.text = "Compiling..."
                binding.statusSubtext.isVisible = false
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.Building -> {
                binding.statusText.text = "Building project..."
                binding.statusSubtext.text = "First build may take 10-15 minutes"
                binding.statusSubtext.isVisible = true
                binding.loadingIndicator.isVisible = true
            }
            is PreviewState.NeedsBuild -> {
                LOG.debug("Build required for multi-file preview support")
            }
            is PreviewState.Empty -> {
                LOG.debug("No preview composables found")
            }
            is PreviewState.Ready -> {
                LOG.info(
                    "Runtime DEX files from state: {}, project DEX files: {}",
                    state.runtimeDex.size,
                    state.projectDexFiles.size,
                )

                state.runtimeDex.forEach {
                    LOG.debug("  Runtime DEX: {}", it.absolutePath)
                }

                classLoader?.setProjectDexFiles(state.projectDexFiles)

                classLoader?.setRuntimeDexFiles(state.runtimeDex)

                if (viewModel.displayMode.value == DisplayMode.ALL) {
                    renderAllPreviews(state)
                } else {
                    val selected = viewModel.selectedPreview.value
                    if (selected != null) {
                        renderSinglePreview(state, selected)
                    }
                }
            }
            is PreviewState.Error -> {
                binding.errorMessage.text = state.message
                val details =
                    if (state.diagnostics.isNotEmpty()) {
                        state.diagnostics.joinToString("\n\n") { diagnostic ->
                            buildString {
                                if (diagnostic.file != null || diagnostic.line != null) {
                                    diagnostic.file?.let { append(it.substringAfterLast('/')) }
                                    diagnostic.line?.let { append(":$it") }
                                    diagnostic.column?.let { append(":$it") }
                                    append("\n")
                                }
                                append("[${diagnostic.severity}] ${diagnostic.message}")
                            }
                        }
                    } else {
                        state.message
                    }
                binding.errorDetails.text = details
                binding.errorDetails.isVisible = true
                binding.errorBuildButton.isVisible = viewModel.canTriggerBuild()

                LOG.error("Preview error: {}", state.message)
                LOG.error("Diagnostics: {}", details)
            }
        }
    }

    private fun updateDisplayMode(mode: DisplayMode) {
        val isAllMode = mode == DisplayMode.ALL

        toggleMenuItem?.setIcon(
            if (isAllMode) R.drawable.ic_view_single else R.drawable.ic_view_grid
        )

        binding.previewSelector.isVisible = !isAllMode && viewModel.availablePreviews.value.size > 1

        val state = viewModel.previewState.value
        if (state is PreviewState.Ready) {
            updatePreviewVisibility(state)

            if (isAllMode) {
                renderAllPreviews(state)
            } else {
                val selected = viewModel.selectedPreview.value
                if (selected != null) {
                    renderSinglePreview(state, selected)
                }
            }
        }
    }

    private fun updatePreviewSelector(previews: List<String>) {
        selectorAdapter?.clear()
        selectorAdapter?.addAll(previews)
        selectorAdapter?.notifyDataSetChanged()

        binding.previewSelector.isVisible =
            viewModel.displayMode.value == DisplayMode.SINGLE && previews.size > 1

        val selected = viewModel.selectedPreview.value
        if (selected != null) {
            val position = previews.indexOf(selected)
            if (position >= 0) {
                binding.previewSelector.setSelection(position)
            }
        }
    }

    private fun renderAllPreviews(state: PreviewState.Ready) {
        val container = binding.previewListContainer
        val loader = classLoader ?: return

        val functionNames = state.previewConfigs.map { it.functionName }
        LOG.debug(
            "renderAllPreviews called with {} functions: {}",
            functionNames.size,
            functionNames,
        )

        val currentFunctions = multiRenderers.keys.toSet()
        val newFunctions = functionNames.toSet()

        if (currentFunctions == newFunctions) {
            LOG.debug("Same functions, re-rendering existing views")
            functionNames.forEach { functionName ->
                multiRenderers[functionName]?.render(
                    dexFile = state.dexFile,
                    className = state.className,
                    functionName = functionName,
                )
            }
            return
        }

        LOG.debug("Creating new preview items")
        container.removeAllViews()
        multiRenderers.clear()

        state.previewConfigs.forEachIndexed { index, config ->
            LOG.debug("Adding preview item {}: {}", index, config.functionName)
            val previewItem = createPreviewItem(config.functionName, index == 0)
            container.addView(previewItem)

            val boundedView = previewItem.findViewById<BoundedComposeView>(R.id.composePreview)

            config.heightDp?.let { heightDp ->
                boundedView.explicitHeightPx = (heightDp * resources.displayMetrics.density).toInt()
            }

            boundedView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )

            val renderer = ComposableRenderer(boundedView.composeView, loader)
            multiRenderers[config.functionName] = renderer

            renderer.render(
                dexFile = state.dexFile,
                className = state.className,
                functionName = config.functionName,
            )
        }

        LOG.debug("Container now has {} children", container.childCount)
    }

    private fun renderSinglePreview(state: PreviewState.Ready, functionName: String) {
        val renderer = if (deviceProfile != null) framedRenderer else singleRenderer
        renderer?.render(
            dexFile = state.dexFile,
            className = state.className,
            functionName = functionName,
        )
    }

    private fun createPreviewItem(functionName: String, isFirst: Boolean): View {
        val item =
            layoutInflater.inflate(R.layout.item_preview_card, binding.previewListContainer, false)

        item.findViewById<TextView>(R.id.previewLabel)?.let { label ->
            label.text = "@$functionName"
        }

        item.findViewById<View>(R.id.divider)?.let { divider ->
            divider.isVisible = !isFirst
        }

        return item
    }

    override fun onDestroy() {
        super.onDestroy()
        multiRenderers.clear()
        singleRenderer = null
        framedRenderer = null
        framedComposeView = null
        deviceFrameView = null
        classLoader?.release()
        classLoader = null
        selectorAdapter = null
        toggleMenuItem = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        classLoader?.release()
        LOG.warn("Low memory - released preview resources")
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewActivity::class.java)

        private const val EXTRA_SOURCE_CODE = "source_code"
        private const val EXTRA_FILE_PATH = "file_path"

        fun start(context: Context, sourceCode: String, filePath: String) {
            val intent =
                Intent(context, ComposePreviewActivity::class.java).apply {
                    putExtra(EXTRA_SOURCE_CODE, sourceCode)
                    putExtra(EXTRA_FILE_PATH, filePath)
                }
            context.startActivity(intent)
        }
    }
}
