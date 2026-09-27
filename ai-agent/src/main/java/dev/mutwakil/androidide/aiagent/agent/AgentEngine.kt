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

package dev.mutwakil.androidide.aiagent.agent

import com.google.gson.JsonParser
import dev.mutwakil.androidide.aiagent.agent.tools.AnalyzeImageTool
import dev.mutwakil.androidide.aiagent.agent.ConditionalConfirmation
import dev.mutwakil.androidide.aiagent.agent.tools.summarizeArgs
import dev.mutwakil.androidide.aiagent.context.ProjectContextIndexer
import dev.mutwakil.androidide.aiagent.context.ProjectSnapshot
import dev.mutwakil.androidide.aiagent.gate.CapabilityGate
import dev.mutwakil.androidide.aiagent.integration.BuildBridge
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.AttachmentType
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ChatMessage
import dev.mutwakil.androidide.aiagent.model.ChatRequest
import dev.mutwakil.androidide.aiagent.model.ChatResponse
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.model.Role
import dev.mutwakil.androidide.aiagent.model.ToolCall
import dev.mutwakil.androidide.aiagent.model.ToolDefinition
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor do agente de desenvolvimento.
 *
 * ## Fluxo (modo [AgentMode.AGENT])
 * 1. **UNDERSTAND**: emite `Status("Analyzing project...")` e monta o
 *    system prompt (resumo do projeto via [ProjectContextIndexer], lista de
 *    tools disponíveis filtradas pelo [CapabilityGate], regras de honestidade).
 * 2. **PLAN**: para tarefas complexas (heurística em [needsPlan]), pede ao
 *    modelo um plano em JSON `{"steps": [...]}` e emite [AgentEvent.Plan].
 * 3. **IMPLEMENT**: loop de tool-calling (máx [MAX_TOOL_ROUNDS] iterações).
 *    Antes, cria snapshot via [ActionHistory.createSnapshot]. Cada [ToolCall]
 *    passa por checagem de permissão ([PermissionManager.grants]),
 *    confirmação ([PermissionManager.requireConfirmation]) e execução com
 *    registro de arquivos modificados.
 * 4. **EXECUTE/TEST**: se o usuário pediu build/teste, ou houve modificação
 *    em código e `canBuild`, roda `build_project` → `run_tests`.
 * 5. **ANALYZE/FIX**: se falhou, alimenta os logs ao modelo e tenta corrigir
 *    (até [MAX_FIX_ATTEMPTS] tentativas).
 * 6. **Resultado**: [AgentEvent.Completed] com resumo + arquivos modificados;
 *    o registro vai para o [ActionHistory].
 *
 * No modo [AgentMode.CHAT], o engine só repassa a mensagem ao provider
 * (streaming) sem tools.
 *
 * O cancelamento é cooperativo: [cancel] marca a flag e cada etapa checa
 * antes de prosseguir, emitindo [AgentEvent.Cancelled].
 *
 * ## Nota de integração (front-a)
 * Usa [ProjectContextIndexer.snapshot] com o [projectRoot] deste engine.
 */
class AgentEngine(
    private val plugin: AiProviderPlugin,
    private val config: ProviderConfig,
    tools: List<AgentTool>,
    private val projectRoot: File,
    private val mode: AgentMode,
    private val permissions: PermissionManager,
    private val buildBridge: BuildBridge?,
    private val history: ActionHistory,
    private val contextIndexer: ProjectContextIndexer
) {
    private val cancelled = AtomicBoolean(false)
    private val allTools: List<AgentTool> = tools.toList()

    /** Capabilities do provider resolvidas para o modelo configurado. */
    private val resolvedCaps: Set<Capability> by lazy { plugin.resolvedCapabilities(config) }

    /** Solicita o cancelamento cooperativo da execução em curso. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Executa o agente para [userMessage], emitindo [AgentEvent]s.
     * O fluxo termina com [AgentEvent.Completed], [AgentEvent.Failed] ou
     * [AgentEvent.Cancelled].
     */
    fun run(userMessage: ChatMessage): Flow<AgentEvent> = channelFlow {
        val toolCtx = ToolContext(
            projectRoot = projectRoot,
            buildBridge = buildBridge,
            permissions = permissions,
            history = history,
            onStatus = { text -> trySend(AgentEvent.Status(text)) },
            isCancelled = { cancelled.get() }
        )
        try {
            send(AgentEvent.Status("Analyzing project..."))
            when (mode) {
                AgentMode.CHAT -> runChat(userMessage, toolCtx)
                AgentMode.AGENT -> runAgent(userMessage, toolCtx)
            }
        } catch (e: CancellationException) {
            trySend(AgentEvent.Cancelled)
        } catch (e: Exception) {
            trySend(AgentEvent.Failed(e.message ?: "Erro desconhecido no agente"))
        }
    }

    // ------------------------------------------------------------------
    // Modo CHAT
    // ------------------------------------------------------------------

    private suspend fun ProducerScope<AgentEvent>.runChat(
        userMessage: ChatMessage,
        toolCtx: ToolContext
    ) {
        val systemPrompt = buildSystemPrompt(emptyList())
        send(AgentEvent.Status("Thinking..."))
        val full = StringBuilder()
        var streamed = false
        try {
            plugin.streamChat(
                ChatRequest(listOf(userMessage), systemPrompt = systemPrompt),
                config
            ).collect { chunk ->
                ensureNotCancelled(toolCtx)
                if (chunk.deltaText.isNotEmpty()) {
                    streamed = true
                    full.append(chunk.deltaText)
                    send(AgentEvent.AssistantDelta(chunk.deltaText))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fallback: provider sem streaming funcional → chamada única.
            if (!streamed) {
                ensureNotCancelled(toolCtx)
                val response = plugin.chat(
                    ChatRequest(listOf(userMessage), systemPrompt = systemPrompt),
                    config
                )
                full.append(response.text)
                send(AgentEvent.AssistantDelta(response.text))
            }
        }
        send(AgentEvent.Completed(full.toString()))
    }

    // ------------------------------------------------------------------
    // Modo AGENT
    // ------------------------------------------------------------------

    private suspend fun ProducerScope<AgentEvent>.runAgent(
        userMessage: ChatMessage,
        toolCtx: ToolContext
    ) {
        // Tools filtradas pelas capabilities honestas do provider.
        val usableDefs = CapabilityGate.filterTools(
            resolvedCaps,
            allTools.map { it.definition }
        )
        val toolMap = allTools
            .filter { tool -> usableDefs.any { it.name == tool.definition.name } }
            .associateBy { it.definition.name }

        val systemPrompt = buildSystemPrompt(usableDefs)

        if (toolMap.isEmpty()) {
            // Provider sem tool-calling: degrada para resposta direta, sem fingir.
            send(AgentEvent.Status("Provider sem tool-calling; respondendo sem ferramentas."))
            val response = plugin.chat(
                ChatRequest(listOf(userMessage), systemPrompt = systemPrompt),
                config
            )
            send(AgentEvent.Completed(response.text))
            return
        }

        // PLAN (só para tarefas complexas)
        val plan = if (needsPlan(userMessage)) {
            send(AgentEvent.Status("Planning..."))
            generatePlan(userMessage, systemPrompt).also { steps ->
                if (steps.isNotEmpty()) send(AgentEvent.Plan(steps))
            }
        } else {
            emptyList()
        }

        val conversation = mutableListOf(userMessage)
        val modified = mutableSetOf<String>()
        val actions = mutableListOf<String>()
        var lastText = ""

        try {
            // Snapshot ANTES de qualquer modificação (best-effort).
            try {
                history.createSnapshot(projectRoot)
            } catch (_: Exception) {
                send(AgentEvent.Status("Aviso: não foi possível criar snapshot de segurança."))
            }

            // IMPLEMENT: loop de tool-calling.
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                ensureNotCancelled(toolCtx)
                round++
                send(AgentEvent.Status("Agent working… (passo $round/$MAX_TOOL_ROUNDS)"))
                val response = chatRound(conversation, systemPrompt, usableDefs, toolMap, toolCtx, modified, actions)
                lastText = response.text
                if (response.toolCalls.isEmpty()) break
            }

            // EXECUTE / TEST / ANALYZE / FIX
            if (shouldBuildOrTest(userMessage.text, modified)) {
                runBuildFixLoop(conversation, systemPrompt, usableDefs, toolMap, toolCtx, modified, actions)
            } else if (BUILD_KEYWORDS.any { userMessage.text.contains(it, ignoreCase = true) }) {
                // Usuário pediu build/teste, mas está indisponível: avisa em vez de fingir.
                val reason = if (buildBridge == null) {
                    "BuildBridge indisponível no app"
                } else {
                    "build desabilitado nas permissões (canBuild=false)"
                }
                actions.add("build/testes pulado: $reason")
                send(AgentEvent.Status("Build pulado: $reason."))
            }

            val summary = buildSummary(lastText, modified, actions)
            // I/O de disco: fora da thread do coletor.
            withContext(Dispatchers.IO) {
                history.record(
                    userCommand = userMessage.text,
                    plan = plan,
                    filesModified = modified.toList(),
                    actionsPerformed = actions,
                    result = summary
                )
            }
            send(AgentEvent.Completed(summary))
        } finally {
            // NonCancellable: o fechamento do snapshot precisa acontecer
            // mesmo se a coroutine foi cancelada.
            withContext(NonCancellable + Dispatchers.IO) {
                history.closeCurrentSnapshot()
            }
        }
    }

    /**
     * Uma rodada: chama o modelo com as tools e executa todos os
     * [ToolCall]s retornados, anexando os resultados à conversa.
     */
    private suspend fun ProducerScope<AgentEvent>.chatRound(
        conversation: MutableList<ChatMessage>,
        systemPrompt: String,
        toolDefs: List<ToolDefinition>,
        toolMap: Map<String, AgentTool>,
        toolCtx: ToolContext,
        modified: MutableSet<String>,
        actions: MutableList<String>
    ): ChatResponse {
        ensureNotCancelled(toolCtx)
        val response = plugin.chat(
            ChatRequest(messages = conversation.toList(), tools = toolDefs, systemPrompt = systemPrompt),
            config
        )
        ensureNotCancelled(toolCtx)
        conversation.add(ChatMessage(Role.ASSISTANT, response.text))

        for (call in response.toolCalls) {
            ensureNotCancelled(toolCtx)
            val tool = toolMap[call.name]
            send(AgentEvent.ToolStarted(call.name, summarizeArgs(call)))
            val result = if (tool == null) {
                ToolResult.error(
                    "Tool '${call.name}' não existe. Disponíveis: ${toolMap.keys.sorted().joinToString(", ")}"
                )
            } else {
                executeToolCall(tool, call, toolCtx)
            }
            modified.addAll(toolCtx.history.takePendingModified())
            actions.add("${call.name}: ${if (result.success) "ok" else "falhou"}")
            send(AgentEvent.ToolFinished(call.name, result.success))
            conversation.add(
                ChatMessage(Role.TOOL, result.output, toolCallId = call.id, toolName = call.name)
            )
        }
        return response
    }

    /**
     * Executa um [ToolCall] com as proteções:
     * permissão ([PermissionManager.grants]) → confirmação (se exigida) →
     * execução → interceptação multimodal (visão via provider).
     */
    private suspend fun ProducerScope<AgentEvent>.executeToolCall(
        tool: AgentTool,
        call: ToolCall,
        toolCtx: ToolContext
    ): ToolResult {
        // 1. Permissão pelo tipo da tool.
        permissionDeniedReason(call.name)?.let { reason ->
            return ToolResult.error("Permissão negada: $reason")
        }

        // 2. Confirmação para ações sensíveis.
        val needsConfirm = if (tool is ConditionalConfirmation) {
            tool.requiresConfirmationFor(call.argumentsJson)
        } else {
            tool.requiresConfirmation
        }
        if (needsConfirm) {
            send(AgentEvent.Status("Aguardando confirmação: ${tool.definition.name}"))
            val confirmed = toolCtx.permissions.requireConfirmation(
                tool.definition.name,
                summarizeArgs(call)
            )
            if (!confirmed) {
                return ToolResult.error("Ação cancelada: usuário não confirmou '${tool.definition.name}'.")
            }
        }

        // 3. Execução.
        val result = try {
            tool.execute(call.argumentsJson, toolCtx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Exceção na tool '${tool.definition.name}': ${e.message}")
        }

        // 4. Interceptação multimodal: análise de imagem via provider VISION.
        if (result.success && (call.name == "analyze_image" || call.name == "compare_images")) {
            return visionFollowUp(call, toolCtx)
        }
        return result
    }

    /**
     * Reenvia a(s) imagem(ns) ao provider como anexo(s), com a pergunta —
     * somente se o provider declarar [Capability.VISION].
     * Sem VISION, retorna erro honesto (nunca finge a análise).
     */
    private suspend fun ProducerScope<AgentEvent>.visionFollowUp(
        call: ToolCall,
        toolCtx: ToolContext
    ): ToolResult {
        if (!resolvedCaps.contains(Capability.VISION)) {
            return ToolResult.error(
                CapabilityGate.unavailableMessage(plugin.displayName, Capability.VISION)
            )
        }
        val args = try {
            JsonParser.parseString(call.argumentsJson).asJsonObject
        } catch (_: Exception) {
            return ToolResult.error("Argumentos inválidos para '${call.name}'.")
        }
        fun arg(name: String): String? =
            if (args.has(name) && args.get(name).isJsonPrimitive) args.get(name).asString else null

        val attachments = mutableListOf<Attachment>()
        val question: String
        // Revalida o confinamento ao projectRoot (a tool já validou, defesa em profundidade).
        fun safeFile(path: String): File? {
            val root = toolCtx.projectRoot.canonicalFile
            val file = File(root, path).canonicalFile
            return if (file == root || file.startsWith(root)) file else null
        }
        when (call.name) {
            "analyze_image" -> {
                val path = arg("imagePath") ?: return ToolResult.error("imagePath ausente.")
                question = arg("question") ?: "Descreva esta imagem em detalhes."
                val file = safeFile(path) ?: return ToolResult.error("Caminho fora do projeto: $path")
                // Revalida o teto da tool: o arquivo pode ter crescido entre a
                // validação e o envio ao provider.
                if (file.length() > AnalyzeImageTool.MAX_IMAGE_BYTES) {
                    return ToolResult.error(
                        "Imagem acima do limite (${AnalyzeImageTool.MAX_IMAGE_BYTES} bytes): $path"
                    )
                }
                attachments.add(
                    Attachment(AttachmentType.IMAGE, file, AnalyzeImageTool.mimeFor(file.extension), file.name)
                )
            }
            "compare_images" -> {
                val pathA = arg("imageA") ?: return ToolResult.error("imageA ausente.")
                val pathB = arg("imageB") ?: return ToolResult.error("imageB ausente.")
                question = "Compare estas duas imagens em detalhes: layout, cores, textos, " +
                    "diferenças visuais e possíveis problemas. Responda em português."
                for (p in listOf(pathA, pathB)) {
                    val file = safeFile(p) ?: return ToolResult.error("Caminho fora do projeto: $p")
                    if (file.length() > AnalyzeImageTool.MAX_IMAGE_BYTES) {
                        return ToolResult.error(
                            "Imagem acima do limite (${AnalyzeImageTool.MAX_IMAGE_BYTES} bytes): $p"
                        )
                    }
                    attachments.add(
                        Attachment(AttachmentType.IMAGE, file, AnalyzeImageTool.mimeFor(file.extension), file.name)
                    )
                }
            }
            else -> return ToolResult.error("Tool multimodal desconhecida: ${call.name}")
        }

        send(AgentEvent.Status("Analisando imagem(ns) com ${plugin.displayName}..."))
        ensureNotCancelled(toolCtx)
        val response = plugin.chat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, question, attachments)),
                systemPrompt = "Você é um analisador de imagens. Descreva apenas o que " +
                    "realmente vê nas imagens anexadas. Nunca invente detalhes."
            ),
            config
        )
        return ToolResult.ok(response.text)
    }

    // ------------------------------------------------------------------
    // Build / test / fix
    // ------------------------------------------------------------------

    private fun shouldBuildOrTest(userText: String, modified: Set<String>): Boolean {
        if (!permissions.grants.canBuild || buildBridge == null) return false
        val wantsBuild = BUILD_KEYWORDS.any { userText.contains(it, ignoreCase = true) }
        val codeChanged = modified.any { path ->
            val lower = path.lowercase()
            lower.endsWith(".kt") || lower.endsWith(".java") || lower.endsWith(".xml") ||
                lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")
        }
        return wantsBuild || codeChanged
    }

    /** Roda build + testes; se falhar, tenta corrigir até [MAX_FIX_ATTEMPTS] vezes. */
    private suspend fun ProducerScope<AgentEvent>.runBuildFixLoop(
        conversation: MutableList<ChatMessage>,
        systemPrompt: String,
        toolDefs: List<ToolDefinition>,
        toolMap: Map<String, AgentTool>,
        toolCtx: ToolContext,
        modified: MutableSet<String>,
        actions: MutableList<String>
    ) {
        var attempt = 0
        var ok = executeBuildAndTests(conversation, toolCtx, toolMap, modified, actions)
        while (!ok && attempt < MAX_FIX_ATTEMPTS) {
            ensureNotCancelled(toolCtx)
            attempt++
            send(AgentEvent.Status("Analyzing failure… (tentativa de correção $attempt/$MAX_FIX_ATTEMPTS)"))
            val logs = try {
                buildBridge?.recentLogs(120)?.takeLast(60)?.joinToString("\n").orEmpty()
            } catch (_: Exception) {
                ""
            }
            conversation.add(
                ChatMessage(
                    Role.USER,
                    "O build ou os testes FALHARAM. Analise o resumo e os logs abaixo, " +
                        "corrija o código usando as tools (read_file, edit_file etc.) e " +
                        "explique o que foi corrigido.\n\nLOGS RECENTES:\n$logs"
                )
            )
            chatRound(conversation, systemPrompt, toolDefs, toolMap, toolCtx, modified, actions)
            ok = executeBuildAndTests(conversation, toolCtx, toolMap, modified, actions)
        }
        if (!ok) {
            actions.add("build/testes: falhou após $MAX_FIX_ATTEMPTS tentativa(s) de correção")
        }
    }

    /** Executa `build_project` e `run_tests` via tools, anexando resultados. */
    private suspend fun ProducerScope<AgentEvent>.executeBuildAndTests(
        conversation: MutableList<ChatMessage>,
        toolCtx: ToolContext,
        toolMap: Map<String, AgentTool>,
        modified: MutableSet<String>,
        actions: MutableList<String>
    ): Boolean {
        val buildTool = toolMap["build_project"]
            ?: return false.also { actions.add("build_project: tool indisponível") }
        val buildCall = ToolCall("build-${System.currentTimeMillis()}", "build_project", "{}")
        send(AgentEvent.ToolStarted("build_project", "assembleDebug"))
        val buildResult = executeToolCall(buildTool, buildCall, toolCtx)
        send(AgentEvent.ToolFinished("build_project", buildResult.success))
        modified.addAll(toolCtx.history.takePendingModified())
        actions.add("build_project: ${if (buildResult.success) "ok" else "falhou"}")
        conversation.add(
            ChatMessage(Role.TOOL, buildResult.output, toolCallId = buildCall.id, toolName = "build_project")
        )
        if (!buildResult.success) return false

        val testTool = toolMap["run_tests"] ?: return true
        val testCall = ToolCall("test-${System.currentTimeMillis()}", "run_tests", "{}")
        send(AgentEvent.ToolStarted("run_tests", "testDebugUnitTest"))
        val testResult = executeToolCall(testTool, testCall, toolCtx)
        send(AgentEvent.ToolFinished("run_tests", testResult.success))
        actions.add("run_tests: ${if (testResult.success) "ok" else "falhou"}")
        conversation.add(
            ChatMessage(Role.TOOL, testResult.output, toolCallId = testCall.id, toolName = "run_tests")
        )
        return testResult.success
    }

    // ------------------------------------------------------------------
    // Planejamento
    // ------------------------------------------------------------------

    /** Heurística: tarefa "complexa" merece plano visível? */
    private fun needsPlan(message: ChatMessage): Boolean {
        if (message.attachments.isNotEmpty()) return true
        val text = message.text
        if (text.length > 150) return true
        return PLAN_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    private suspend fun generatePlan(userMessage: ChatMessage, systemPrompt: String): List<String> {
        return try {
            val response = plugin.chat(
                ChatRequest(
                    messages = listOf(userMessage),
                    systemPrompt = systemPrompt +
                        "\n\nTAREFA: gere um plano de ação numerado para o pedido acima. " +
                        "Responda SOMENTE com JSON válido no formato: {\"steps\": [\"passo 1\", \"passo 2\"]}."
                ),
                config
            )
            parsePlanJson(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePlanJson(text: String): List<String> {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val obj = JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
            val steps = obj.getAsJsonArray("steps") ?: return emptyList()
            steps.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Permissões
    // ------------------------------------------------------------------

    /** Retorna o motivo da negação, ou `null` se a tool pode executar. */
    private fun permissionDeniedReason(toolName: String): String? {
        val g = permissions.grants
        return when (toolName) {
            "read_file", "search_code", "project_search", "git_diff", "read_build_logs" ->
                if (!g.canRead) "leitura desabilitada (canRead=false)" else null
            "create_file", "edit_file" ->
                if (!g.canModify) "modificação de arquivos desabilitada (canModify=false)" else null
            "delete_file" ->
                if (!g.canDelete) "exclusão desabilitada (canDelete=false)" else null
            "run_command" ->
                if (!g.canRunCommands) "execução de comandos desabilitada (canRunCommands=false)" else null
            "build_project", "run_tests" ->
                if (!g.canBuild) "build desabilitado (canBuild=false)" else null
            "undo_changes" ->
                if (!g.canModify) "modificação de arquivos desabilitada (canModify=false)" else null
            "analyze_image", "analyze_document", "compare_images" ->
                if (!g.canRead) "leitura desabilitada (canRead=false)" else null
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // System prompt
    // ------------------------------------------------------------------

    private suspend fun buildSystemPrompt(toolDefs: List<ToolDefinition>): String {
        val toolsDesc = if (toolDefs.isEmpty()) {
            "(nenhuma tool disponível)"
        } else {
            toolDefs.joinToString("\n") { "- ${it.name}: ${it.description}" }
        }
        return """
            Você é o "AI Development Agent" dentro do AndroidIDE, um assistente de programação com acesso a ferramentas reais sobre o projeto do usuário.

            PROJETO
            ${projectDescription()}

            FERRAMENTAS DISPONÍVEIS (use via tool calls; nunca descreva a chamada em texto)
            $toolsDesc

            REGRAS OBRIGATÓRIAS
            1. Responda em português (pt-BR), de forma direta e objetiva.
            2. HONESTIDADE SOBRE CAPACIDADES — a regra mais importante:
               - NUNCA afirme ter analisado uma imagem, documento, build ou log se você NÃO executou a tool correspondente ou NÃO recebeu o resultado/anexo.
               - Se precisar ver uma imagem, chame analyze_image; se o provider não tiver visão, diga isso claramente em vez de inventar a análise.
               - Não diga "analisei o código" se não chamou read_file/search_code.
            3. Antes de editar, LEIA o código relevante (read_file/search_code). Não adivinhe APIs: confira no projeto.
            4. edit_file exige o trecho oldText EXATO (copie de read_file). Se falhar, releia o arquivo em vez de insistir no mesmo trecho.
            5. Saídas de tools são truncadas: se precisar de mais contexto, refine a busca em vez de pedir o arquivo inteiro de novo.
            6. delete_file e comandos fora da allowlist pedem confirmação do usuário — explique brevemente o porquê antes.
            7. Não execute comandos destrutivos; eles são bloqueados automaticamente.
            8. Ao final de tarefas de código, resuma em poucas linhas: o que foi feito + arquivos modificados.
        """.trimIndent()
    }

    private suspend fun projectDescription(): String {
        try {
            val snapshot: ProjectSnapshot = contextIndexer.snapshot(projectRoot)
            val text = snapshot.toString()
            if (text.isNotBlank()) {
                return "Resumo do índice do projeto:\n" + text.take(1500)
            }
        } catch (_: Exception) {
            // Cai para o levantamento próprio abaixo.
        }
        return fallbackProjectDescription()
    }

    private suspend fun fallbackProjectDescription(): String {
        return try {
            // I/O de disco: fora da thread do coletor.
            withContext(Dispatchers.IO) {
                val top = projectRoot.listFiles()
                    ?.filter { it.name !in setOf("build", ".git", ".gradle") }
                    ?.take(15)
                    ?.joinToString(", ") { it.name + if (it.isDirectory) "/" else "" }
                    ?: "?"
                var kt = 0
                var java = 0
                var xml = 0
                projectRoot.walkTopDown()
                    .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" }
                    .take(MAX_FALLBACK_SCAN_FILES)
                    .forEach { file ->
                        if (!file.isFile) return@forEach
                        when (file.extension.lowercase()) {
                            "kt", "kts" -> kt++
                            "java" -> java++
                            "xml" -> xml++
                        }
                    }
                "Projeto '${projectRoot.name}' em ${projectRoot.absolutePath}. " +
                    "Topo: [$top]. Aproximadamente: $kt arquivos Kotlin, $java Java, $xml XML."
            }
        } catch (_: Exception) {
            "Projeto em ${projectRoot.absolutePath}."
        }
    }

    // ------------------------------------------------------------------
    // Resumo final
    // ------------------------------------------------------------------

    private fun buildSummary(lastText: String, modified: Set<String>, actions: List<String>): String {
        val sb = StringBuilder()
        if (lastText.isNotBlank()) {
            sb.appendLine(lastText.take(2000).trim())
        }
        val failures = actions.count { it.endsWith("falhou") }
        if (actions.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Ações: ${actions.size - failures} ok, $failures com falha.")
        }
        if (modified.isNotEmpty()) {
            sb.appendLine("Arquivos modificados (${modified.size}):")
            modified.sorted().take(30).forEach { sb.appendLine("  - $it") }
            if (modified.size > 30) sb.appendLine("  … e mais ${modified.size - 30}.")
        }
        return sb.toString().trim().ifEmpty { "Tarefa concluída." }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun ensureNotCancelled(toolCtx: ToolContext) {
        if (cancelled.get() || toolCtx.isCancelled()) {
            throw CancellationException("Execução cancelada pelo usuário")
        }
    }

    companion object {
        /** Máximo de rodadas de tool-calling na fase IMPLEMENT. */
        const val MAX_TOOL_ROUNDS: Int = 15

        /** Teto de arquivos varridos no levantamento de fallback (evita ANR em projetos grandes). */
        const val MAX_FALLBACK_SCAN_FILES: Int = 3000

        /** Máximo de tentativas de correção após falha de build/teste. */
        const val MAX_FIX_ATTEMPTS: Int = 3

        private val PLAN_KEYWORDS: List<String> = listOf(
            "creat", "criar", "crie", "fix", "corrig", "consert", "build",
            "compil", "implement", "refactor", "refator", "add", "adicion",
            "migrat", "otimiz", "test", "feature", "tela", "screen"
        )

        private val BUILD_KEYWORDS: List<String> = listOf(
            "build", "compil", "teste", "testar", "test", "rodar", "run"
        )
    }
}
