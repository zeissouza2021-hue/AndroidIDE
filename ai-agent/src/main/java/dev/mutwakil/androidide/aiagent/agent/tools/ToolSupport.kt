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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mutwakil.androidide.aiagent.agent.AgentTool
import dev.mutwakil.androidide.aiagent.agent.ToolContext
import dev.mutwakil.androidide.aiagent.agent.ToolResult
import dev.mutwakil.androidide.aiagent.model.ToolCall
import dev.mutwakil.androidide.aiagent.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Base para as 16 tools do agente.
 *
 * Oferece:
 * - [guard]: checagem de cancelamento, conversão de exceções em
 *   [ToolResult] de erro e truncamento da saída em ~8k chars.
 *   [CancellationException] sempre propaga (cancelamento cooperativo).
 * - [resolveInRoot]: resolve um caminho relativo dentro do projectRoot,
 *   **bloqueando path traversal** (normaliza e exige que o caminho canônico
 *   fique dentro da raiz).
 * - Helpers de parsing de [argsJson] via Gson.
 */
abstract class BaseTool(
    override val definition: ToolDefinition,
    override val requiresConfirmation: Boolean = false
) : AgentTool {

    protected val gson: Gson = Gson()

    /**
     * Executa [block] com as proteções padrão.
     * Use como corpo de [execute] em todas as tools.
     */
    protected suspend fun guard(ctx: ToolContext, block: suspend () -> ToolResult): ToolResult {
        return try {
            ctx.checkCancelled()
            val result = block()
            result.copy(output = result.output.truncateForTool())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult.error("Operação bloqueada por segurança: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Resolve [rawPath] (relativo ou absoluto) confinando-o ao projectRoot.
     *
     * @throws SecurityException se o caminho canônico escapar da raiz.
     */
    protected fun resolveInRoot(ctx: ToolContext, rawPath: String): File {
        require(rawPath.isNotBlank()) { "path vazio" }
        val root = ctx.projectRoot.canonicalFile
        val candidate = File(rawPath)
        val target = if (candidate.isAbsolute) candidate else File(root, rawPath)
        val canonical = target.canonicalFile
        if (canonical != root && !canonical.startsWith(root)) {
            throw SecurityException("path traversal bloqueado: '$rawPath' está fora da raiz do projeto")
        }
        return canonical
    }

    /** Caminho relativo à raiz (com `/`), para histórico e snapshots. */
    protected fun relativeToRoot(ctx: ToolContext, file: File): String {
        val root = ctx.projectRoot.canonicalFile
        val canonical = file.canonicalFile
        return root.toURI().relativize(canonical.toURI()).path.trimEnd('/')
    }

    /** Parse de [argsJson] para [JsonObject] (objeto vazio se inválido/ausente). */
    protected fun parseArgs(argsJson: String): JsonObject {
        return try {
            val el = JsonParser.parseString(argsJson)
            if (el.isJsonObject) el.asJsonObject else JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
    }

    protected fun JsonObject.string(name: String): String? =
        if (has(name) && get(name).isJsonPrimitive) get(name).asString else null

    protected fun JsonObject.stringList(name: String): List<String> =
        if (has(name) && get(name).isJsonArray) {
            get(name).asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
        } else {
            emptyList()
        }

    protected fun JsonObject.int(name: String, default: Int): Int =
        if (has(name) && get(name).isJsonPrimitive) {
            try { get(name).asInt } catch (_: Exception) { default }
        } else {
            default
        }
}

/** Lança [CancellationException] se [ToolContext.isCancelled]. */
fun ToolContext.checkCancelled() {
    if (isCancelled()) throw CancellationException("Execução cancelada pelo usuário")
}

/** Trunca texto para o limite de saída das tools. */
internal fun String.truncateForTool(
    max: Int = ToolResult.MAX_OUTPUT_LENGTH
): String = if (length <= max) this else take(max) + "\n…(saída truncada em $max caracteres)"

/** Resumo curto dos argumentos de um [ToolCall], para [AgentEvent.ToolStarted][dev.mutwakil.androidide.aiagent.agent.AgentEvent.ToolStarted]. */
internal fun summarizeArgs(call: ToolCall): String {
    return try {
        val obj = JsonParser.parseString(call.argumentsJson).asJsonObject
        val parts = obj.entrySet().take(3).map { (k, v) ->
            val value = if (v.isJsonPrimitive) v.asString else v.toString()
            "$k=${value.take(50)}"
        }
        if (parts.isEmpty()) call.name else parts.joinToString(", ")
    } catch (_: Exception) {
        call.argumentsJson.take(80)
    }
}

/** Diretórios ignorados por buscas recursivas. */
internal val SKIP_DIRS: Set<String> = setOf(
    "build", ".git", ".gradle", ".idea", ".cxx", ".kotlin",
    "node_modules", "out", "target", "captures", ".externalNativeBuild"
)

/** Converte glob simples (`*`, `?`) em regex. */
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    for (c in glob) {
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}

/** Heurística simples para pular arquivos binários na busca. */
internal fun isProbablyText(file: File): Boolean {
    val name = file.name.lowercase()
    val textExts = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md",
        "txt", "yml", "yaml", "toml", "cfg", "ini", "csv", "html", "css",
        "js", "ts", "py", "sh", "c", "h", "cpp", "hpp", "pro"
    )
    val ext = name.substringAfterLast('.', "")
    if (ext in textExts) return true
    if (ext.isNotEmpty()) return false
    // Sem extensão: checa os primeiros bytes por NUL.
    return try {
        file.inputStream().use { ins ->
            val buf = ByteArray(512)
            val n = ins.read(buf)
            if (n <= 0) return true
            for (i in 0 until n) if (buf[i] == 0.toByte()) return false
            true
        }
    } catch (_: Exception) {
        false
    }
}
