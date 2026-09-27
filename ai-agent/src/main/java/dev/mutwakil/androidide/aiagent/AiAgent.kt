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

package dev.mutwakil.androidide.aiagent

import android.content.Context
import dev.mutwakil.androidide.aiagent.context.ProjectContextIndexer
import dev.mutwakil.androidide.aiagent.integration.BuildBridgeProvider
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import dev.mutwakil.androidide.aiagent.providers.ProviderRegistry
import dev.mutwakil.androidide.aiagent.store.ProviderConfigStore
import java.io.File

/**
 * Facade singleton for the AI agent system (package `dev.mutwakil.androidide.aiagent`).
 *
 * Typical startup wiring (done by the app, front-f):
 * ```
 * AiAgent.init(appContext)
 * AiAgent.registerProvider(OpenAiCompatibleProvider())
 * AiAgent.registerProvider(AnthropicProvider())
 * AiAgent.registerProvider(GeminiProvider())
 * AiAgent.buildBridgeProvider = GradleBuildBridgeProvider()
 * ```
 */
object AiAgent {

    /** Intent extra carrying the open project root path into [ui.AiChatActivity]. */
    const val EXTRA_PROJECT_PATH = "dev.mutwakil.androidide.aiagent.PROJECT_PATH"

    @Volatile
    private var initialized = false

    private var store: ProviderConfigStore? = null
    private var appContext: Context? = null
    private val registry = ProviderRegistry()
    private val tools = mutableListOf<agent.AgentTool>()
    private val contextIndexer = ProjectContextIndexer()

    /**
     * Set by the app at startup to the front-e [dev.mutwakil.androidide.aiagent.integration.BuildBridge]
     * implementation; null means the agent runs without build integration.
     */
    var buildBridgeProvider: BuildBridgeProvider? = null

    /**
     * Idempotent initialization. Creates the [ProviderConfigStore]; safe to call
     * multiple times and from any thread.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = ProviderConfigStore(context.applicationContext)
        initialized = true
    }

    /** The provider plugin registry. */
    fun registry(): ProviderRegistry = registry

    /** Registers a provider plugin (front-b adapters). */
    fun registerProvider(plugin: AiProviderPlugin) = registry.register(plugin)

    /**
     * Registers an agent tool (front-d) so [newEngine] can hand it to the engine.
     * Tools are also usable standalone via the `agent` package.
     */
    fun registerTool(tool: agent.AgentTool) {
        synchronized(tools) { tools.add(tool) }
    }

    /**
     * Registers the 15 built-in agent tools (file, exec, build and multimodal).
     * Called once by the app at startup; safe to call multiple times.
     */
    fun registerDefaultTools() {
        synchronized(tools) {
            if (tools.isNotEmpty()) return
            tools.addAll(
                listOf(
                    agent.tools.ReadFileTool(),
                    agent.tools.SearchCodeTool(),
                    agent.tools.CreateFileTool(),
                    agent.tools.EditFileTool(),
                    agent.tools.DeleteFileTool(),
                    agent.tools.ProjectSearchTool(),
                    agent.tools.RunCommandTool(),
                    agent.tools.BuildProjectTool(),
                    agent.tools.ReadBuildLogsTool(),
                    agent.tools.RunTestsTool(),
                    agent.tools.GitDiffTool(),
                    agent.tools.UndoChangesTool(),
                    agent.tools.AnalyzeImageTool(),
                    agent.tools.AnalyzeDocumentTool(),
                    agent.tools.CompareImagesTool(),
                )
            )
        }
    }

    /**
     * The configuration store. Throws [IllegalStateException] if [init] was not called.
     */
    fun configStore(): ProviderConfigStore =
        checkNotNull(store) { "AiAgent.init(context) must be called before configStore()" }

    /**
     * Creates an [agent.AgentEngine] (implemented by front-d in the `agent` package)
     * wired with the registered tools, the shared [ProjectContextIndexer] and the
     * current [buildBridgeProvider].
     *
     * @param permissions the [agent.PermissionManager] to use; when null a fresh
     *   one that denies confirmations is created. The chat UI passes its own
     *   instance whose `confirmCallback` shows a dialog to the user.
     */
    fun newEngine(
        plugin: AiProviderPlugin,
        config: ProviderConfig,
        projectRoot: File,
        mode: AgentMode,
        permissions: agent.PermissionManager? = null,
    ): agent.AgentEngine {
        val historyDir = appContext?.let { File(it.cacheDir, "aiagent").apply { mkdirs() } }
        return agent.AgentEngine(
            plugin = plugin,
            config = config,
            tools = synchronized(tools) { tools.toList() },
            projectRoot = projectRoot,
            mode = mode,
            permissions = permissions ?: agent.PermissionManager(),
            buildBridge = buildBridgeProvider?.get(),
            history = agent.ActionHistory(historyDir),
            contextIndexer = contextIndexer,
        )
    }
}
