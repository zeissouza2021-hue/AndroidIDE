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

package dev.mutwakil.androidide.aiagent.agent.tools

import dev.mutwakil.androidide.aiagent.agent.ConditionalConfirmation
import dev.mutwakil.androidide.aiagent.agent.ToolContext
import dev.mutwakil.androidide.aiagent.agent.ToolResult
import dev.mutwakil.androidide.aiagent.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// run_command
// ---------------------------------------------------------------------------

/**
 * Executa um comando shell com cwd = raiz do projeto.
 *
 * Segurança em camadas:
 * 1. **Blocklist** de comandos destrutivos (regex): `rm -rf`, `mkfs`, `dd`,
 *    fork-bomb, `shutdown`, `> /dev/` etc. — sempre bloqueados, sem exceção.
 * 2. **Allowlist** de comandos seguros de leitura (`ls`, `find`, `grep`,
 *    `git status/diff/log/...`, `echo`, `cat`, ...): executam **sem**
 *    confirmação ([ConditionalConfirmation]).
 * 3. Demais comandos: exigem `grants.canRunCommands` (engine) **e**
 *    confirmação do usuário via [PermissionManager][dev.mutwakil.androidide.aiagent.agent.PermissionManager].
 *
 * Execução via `ProcessBuilder("sh", "-c", ...)`, timeout de 120s,
 * stdout+stderr capturados e truncados em ~8k, com checagem cooperativa
 * de cancelamento durante a espera.
 */
class RunCommandTool : BaseTool(
    definition = ToolDefinition(
        name = "run_command",
        description = "Run a shell command in the project dir (asks confirmation unless allowlisted). Executa comando shell no diretório do projeto.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Comando shell a executar (cwd = raiz do projeto)"
                }
              },
              "required": ["command"]
            }
        """.trimIndent()
    ),
    requiresConfirmation = true
), ConditionalConfirmation {

    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val command = parseArgs(argsJson).string("command")?.takeIf { it.isNotBlank() }
            ?: return@guard ToolResult.error("Parâmetro obrigatório: command")

        blockedReason(command)?.let { reason ->
            return@guard ToolResult.error("Comando bloqueado por segurança: $reason")
        }

        ctx.onStatus("Executando: ${command.take(80)}")
        withContext(Dispatchers.IO) {
            runProcess(ctx, command)
        }
    }

    /**
     * `false` para comandos da allowlist (dispensam confirmação);
     * `true` para os demais. Comandos bloqueados nunca chegam aqui
     * (barrados em [execute]).
     */
    override fun requiresConfirmationFor(argsJson: String): Boolean {
        val command = try {
            parseArgs(argsJson).string("command") ?: return true
        } catch (_: Exception) {
            return true
        }
        if (blockedReason(command) != null) {
            // Bloqueado: não pede confirmação à toa — o execute recusa
            // direto com o motivo, sem diálogo inútil no meio.
            return false
        }
        return !isAllowlisted(command)
    }

    private fun runProcess(ctx: ToolContext, command: String): ToolResult {
        val process = ProcessBuilder("sh", "-c", command)
            .directory(ctx.projectRoot)
            .redirectErrorStream(true)
            .start()
        try {
            // Drena a saída numa thread separada para não travar com output grande.
            val output = StringBuilder()
            val reader = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (output.length < OUTPUT_CAP) {
                                output.appendLine(line)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "aiagent-cmd-reader").also { it.isDaemon = true; it.start() }

            // Espera com polling para cancelamento cooperativo + timeout.
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var finished = false
            while (System.currentTimeMillis() < deadline) {
                ctx.checkCancelled()
                if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            reader.join(READER_JOIN_MS)

            if (!finished) {
                process.destroyForcibly()
                return ToolResult.error(
                    "Timeout de ${TIMEOUT_MS / 1000}s excedido para: ${command.take(120)}"
                )
            }
            val exit = process.exitValue()
            val out = output.toString().truncateForTool()
            return if (exit == 0) {
                ToolResult.ok("exit=$exit\n$out")
            } else {
                ToolResult.error("Comando falhou com exit=$exit\n$out")
            }
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        /** Timeout de execução: 120s. */
        const val TIMEOUT_MS: Long = 120_000
        private const val POLL_MS: Long = 500
        private const val READER_JOIN_MS: Long = 2000
        /** Teto de captura da thread leitora (antes do truncamento final). */
        private const val OUTPUT_CAP: Int = 16_384

        /**
         * Comandos (primeiro token) considerados seguros para leitura,
         * dispensados de confirmação. `git` só vale para subcomandos
         * somente-leitura ([SAFE_GIT_SUBCOMMANDS]).
         *
         * `find` e `env` foram removidos: `find` tem `-delete`/`-exec` e
         * `env` executa os argumentos como comando.
         */
        val SAFE_COMMANDS: Set<String> = setOf(
            "ls", "dir", "grep", "egrep", "fgrep", "echo", "cat",
            "pwd", "head", "tail", "wc", "sort", "uniq", "file", "stat",
            "tree", "du", "df", "uname", "printenv", "date",
            "whoami", "hostname", "git"
        )

        /**
         * Subcomandos git somente-leitura liberados na allowlist.
         * `stash` foi removido: sem argumentos ele guarda (muta) o working tree.
         */
        val SAFE_GIT_SUBCOMMANDS: Set<String> = setOf(
            "status", "diff", "log", "show", "branch", "remote",
            "rev-parse", "ls-files", "tag"
        )

        /**
         * Metacaracteres do shell que tiram o comando da allowlist (exigem
         * confirmação). O comando roda via `sh -c`, então a dispensa de
         * confirmação só vale para comandos simples: qualquer um desses
         * permitiria escapar (ex.: `echo $(rm -rf x)`, `echo oi > Main.kt`,
         * `grep senha . | xargs rm`, quebra de linha como separador).
         */
        private val SHELL_METACHARS = setOf(
            '|', '>', '<', '$', '`', '\n', '\r', '(', ')', '&', ';', '{', '}', '!', '~'
        )

        /** Padrões destrutivos/perigosos — sempre bloqueados. */
        val BLOCKED_PATTERNS: List<Regex> = listOf(
            Regex("""(?i)(^|[\s;&|/])rm\s+.*-[a-z]*r"""), // rm -r / rm -rf (inclui /bin/rm)
            Regex("""(?i)\bmkfs\b"""),
            Regex("""(?i)(^|[\s;&|/])dd\b"""), // inclui /bin/dd
            Regex("""(?i):\(\)\s*\{"""), // fork-bomb
            Regex("""(?i)\b(shutdown|reboot|poweroff|halt)\b"""),
            Regex("""(?i)(^|[\s;&|/])(sudo|su)\b"""), // como comando; não dentro de texto ("su" em grep)
            Regex("""(?i)>\s*/dev/"""),
            Regex("""(?i)>\s*/(etc|proc|sys|system)/"""),
            Regex("""(?i)\btee\s+/(dev|proc|sys)/"""), // tee em áreas sensíveis
            Regex("""(?i)\bchmod\s+-R\s+777\s+/"""),
            Regex("""(?i)\b(wget|curl)\b[^|]*\|\s*\S*(sh|bash|python3?|perl|ruby|php)\b"""), // pipe p/ interpretador
            Regex("""(?i)\bformat\b\s+[a-z]:""")
        )

        /** Retorna o motivo do bloqueio, ou `null` se permitido. */
        fun blockedReason(command: String): String? {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return "comando vazio"
            for (pattern in BLOCKED_PATTERNS) {
                if (pattern.containsMatchIn(trimmed)) {
                    return "padrão perigoso detectado ('${pattern.pattern.take(40)}')"
                }
            }
            return null
        }

        /** `true` se o comando está na allowlist segura (sem confirmação). */
        fun isAllowlisted(command: String): Boolean {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return false
            // Via `sh -c`, metacaracteres permitem escapar do comando simples.
            if (SHELL_METACHARS.any { it in trimmed }) return false
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.isEmpty()) return false
            // `..` como segmento de path sai da allowlist (leitura fora do projeto).
            if (tokens.any { it == ".." || it.startsWith("../") || "/../" in it }) return false
            val first = tokens.first().lowercase().removePrefix("./")
            if (first !in SAFE_COMMANDS) return false
            if (first == "git") {
                // Exige subcomando somente-leitura explícito.
                val sub = tokens.getOrNull(1)?.lowercase()?.removePrefix("-") ?: return false
                if (sub !in SAFE_GIT_SUBCOMMANDS) return false
            }
            return true
        }
    }
}

// ---------------------------------------------------------------------------
// build_project
// ---------------------------------------------------------------------------

/**
 * Executa tarefas de build via [BuildBridge][dev.mutwakil.androidide.aiagent.integration.BuildBridge]
 * (default: `assembleDebug`). Exige `grants.canBuild`; sem bridge, erro claro.
 */
class BuildProjectTool : BaseTool(
    definition = ToolDefinition(
        name = "build_project",
        description = "Build the project (Gradle). Compila o projeto (default: assembleDebug).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Tarefas Gradle (default: [\"assembleDebug\"])"
                }
              }
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val bridge = ctx.buildBridge
            ?: return@guard ToolResult.error(
                "Build indisponível: nenhum BuildBridge configurado pelo app."
            )
        val tasks = parseArgs(argsJson).stringList("tasks").ifEmpty { listOf("assembleDebug") }
        ctx.onStatus("Running build... (${tasks.joinToString(" ")})")
        val outcome = bridge.executeTasks(tasks)
        val summary = buildString {
            appendLine("Build ${if (outcome.success) "SUCESSO" else "FALHOU"}: ${tasks.joinToString(" ")}")
            appendLine(outcome.summary)
            if (outcome.failedTasks.isNotEmpty()) {
                appendLine("Tarefas que falharam: ${outcome.failedTasks.joinToString(", ")}")
            }
        }
        if (outcome.success) ToolResult.ok(summary) else ToolResult.error(summary)
    }
}

// ---------------------------------------------------------------------------
// read_build_logs
// ---------------------------------------------------------------------------

/** Lê as linhas recentes do log de build via [BuildBridge][dev.mutwakil.androidide.aiagent.integration.BuildBridge]. */
class ReadBuildLogsTool : BaseTool(
    definition = ToolDefinition(
        name = "read_build_logs",
        description = "Read recent build logs. Lê as linhas recentes do log de build.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "maxLines": {
                  "type": "integer",
                  "description": "Máximo de linhas (default: 200)"
                }
              }
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val bridge = ctx.buildBridge
            ?: return@guard ToolResult.error("Build indisponível: nenhum BuildBridge configurado pelo app.")
        val maxLines = parseArgs(argsJson).int("maxLines", 200).coerceIn(1, 2000)
        val logs = withContext(Dispatchers.IO) { bridge.recentLogs(maxLines) }
        if (logs.isEmpty()) {
            ToolResult.ok("(log de build vazio)")
        } else {
            ToolResult.ok("Últimas ${logs.size} linhas do log:\n" + logs.joinToString("\n"))
        }
    }
}

// ---------------------------------------------------------------------------
// run_tests
// ---------------------------------------------------------------------------

/**
 * Executa testes unitários via [BuildBridge][dev.mutwakil.androidide.aiagent.integration.BuildBridge]
 * (default: `testDebugUnitTest`). Exige `grants.canBuild`.
 */
class RunTestsTool : BaseTool(
    definition = ToolDefinition(
        name = "run_tests",
        description = "Run unit tests (Gradle). Executa os testes unitários (default: testDebugUnitTest).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Tarefas Gradle (default: [\"testDebugUnitTest\"])"
                }
              }
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val bridge = ctx.buildBridge
            ?: return@guard ToolResult.error("Build indisponível: nenhum BuildBridge configurado pelo app.")
        val tasks = parseArgs(argsJson).stringList("tasks").ifEmpty { listOf("testDebugUnitTest") }
        ctx.onStatus("Running tests... (${tasks.joinToString(" ")})")
        val outcome = bridge.executeTasks(tasks)
        val summary = buildString {
            appendLine("Testes ${if (outcome.success) "PASSARAM" else "FALHARAM"}: ${tasks.joinToString(" ")}")
            appendLine(outcome.summary)
            if (outcome.failedTasks.isNotEmpty()) {
                appendLine("Tarefas que falharam: ${outcome.failedTasks.joinToString(", ")}")
            }
        }
        if (outcome.success) ToolResult.ok(summary) else ToolResult.error(summary)
    }
}

// ---------------------------------------------------------------------------
// git_diff
// ---------------------------------------------------------------------------

/**
 * Mostra `git diff --stat` + `git diff` (truncado) via ProcessBuilder.
 * Somente-leitura; erro claro se não for um repositório git.
 */
class GitDiffTool : BaseTool(
    definition = ToolDefinition(
        name = "git_diff",
        description = "Show git diff (stat + patch). Mostra as mudanças não commitadas (somente leitura).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        if (!File(ctx.projectRoot, ".git").isDirectory) {
            return@guard ToolResult.error(
                "O projeto não é um repositório git (pasta .git não encontrada)."
            )
        }
        val stat = runGit(ctx, listOf("git", "diff", "--stat"))
        val patch = runGit(ctx, listOf("git", "diff", "--", "."))
        ToolResult.ok("git diff --stat:\n$stat\n\ngit diff:\n$patch")
    }

    private suspend fun runGit(ctx: ToolContext, cmd: List<String>): String =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(cmd)
                    .directory(ctx.projectRoot)
                    .redirectErrorStream(true)
                    .start()
                val done = process.waitFor(30, TimeUnit.SECONDS)
                if (!done) {
                    process.destroyForcibly()
                    return@withContext "(timeout)"
                }
                val out = process.inputStream.bufferedReader().readText()
                if (process.exitValue() != 0) "(git retornou ${process.exitValue()})\n$out" else out
            } catch (e: Exception) {
                "(falha ao executar git: ${e.message})"
            }
        }.truncateForTool(4000)
}

// ---------------------------------------------------------------------------
// undo_changes
// ---------------------------------------------------------------------------

/**
 * Restaura o snapshot mais recente do [ActionHistory][dev.mutwakil.androidide.aiagent.agent.ActionHistory],
 * desfazendo as modificações feitas pelo agente.
 */
class UndoChangesTool : BaseTool(
    definition = ToolDefinition(
        name = "undo_changes",
        description = "Undo the agent's last changes (restore snapshot). Desfaz as últimas mudanças do agente.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val snapshot = ctx.history.latestSnapshot()
            ?: return@guard ToolResult.error(
                "Nenhum snapshot disponível para desfazer. " +
                    "Snapshots são criados antes das modificações do agente."
            )
        try {
            withContext(Dispatchers.IO) { ctx.history.restoreSnapshot(snapshot) }
        } catch (e: Exception) {
            return@guard ToolResult.error("Falha ao restaurar snapshot: ${e.message}")
        }
        ctx.onStatus("Mudanças desfeitas (snapshot ${snapshot.id})")
        ToolResult.ok(
            "Snapshot ${snapshot.id} restaurado: ${snapshot.entries.size} arquivo(s) " +
                "voltaram ao estado original."
        )
    }
}

/** Lista das tools de execução/build, para montagem do engine. */
fun execTools(): List<BaseTool> = listOf(
    RunCommandTool(),
    BuildProjectTool(),
    ReadBuildLogsTool(),
    RunTestsTool(),
    GitDiffTool(),
    UndoChangesTool()
)
