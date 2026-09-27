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

import dev.mutwakil.androidide.aiagent.agent.ToolContext
import dev.mutwakil.androidide.aiagent.agent.ToolResult
import dev.mutwakil.androidide.aiagent.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------
// read_file
// ---------------------------------------------------------------------------

/**
 * Lê o conteúdo de um arquivo dentro do projectRoot.
 * Bloqueia path traversal; trunca arquivos grandes.
 */
class ReadFileTool : BaseTool(
    definition = ToolDefinition(
        name = "read_file",
        description = "Read a file inside the project. Lê o conteúdo de um arquivo do projeto.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Caminho do arquivo relativo à raiz do projeto (ex.: app/src/main/java/com/exemplo/Main.kt)"
                }
              },
              "required": ["path"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val path = parseArgs(argsJson).string("path")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: path")
        val file = resolveInRoot(ctx, path)
        if (!file.isFile) return@guard ToolResult.error("Arquivo não encontrado: $path")
        if (file.length() > MAX_READ_BYTES) {
            return@guard ToolResult.error(
                "Arquivo muito grande (${file.length()} bytes, limite ${MAX_READ_BYTES}); use search_code para trechos."
            )
        }
        val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        ToolResult.ok("Arquivo: ${relativeToRoot(ctx, file)} (${content.length} chars)\n---\n$content")
    }

    companion object {
        /** Limite de leitura direta (512 KiB). */
        const val MAX_READ_BYTES: Long = 512L * 1024
    }
}

// ---------------------------------------------------------------------------
// search_code
// ---------------------------------------------------------------------------

/**
 * Busca textual recursiva no projeto (grep), pulando `build/`, `.git/`,
 * `.gradle/` etc. Retorna `arquivo:linha:trecho`, até 50 resultados.
 */
class SearchCodeTool : BaseTool(
    definition = ToolDefinition(
        name = "search_code",
        description = "Search code in the project (grep). Busca texto no código do projeto.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Texto a buscar (case-insensitive, substring)"
                },
                "filePattern": {
                  "type": "string",
                  "description": "Filtro glob opcional de arquivos (ex.: *.kt, *.xml)"
                }
              },
              "required": ["query"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val query = args.string("query")?.takeIf { it.isNotBlank() }
            ?: return@guard ToolResult.error("Parâmetro obrigatório: query")
        val filePattern = args.string("filePattern")?.takeIf { it.isNotBlank() }
        val pattern = filePattern?.let { globToRegex(it) }

        val results = withContext(Dispatchers.IO) {
            search(ctx, query, pattern, maxResults = 50)
        }
        if (results.isEmpty()) {
            ToolResult.ok("Nenhum resultado para '$query'${filePattern?.let { " em $it" } ?: ""}.")
        } else {
            ToolResult.ok("${results.size} resultado(s) para '$query':\n" + results.joinToString("\n"))
        }
    }

    companion object {
        /**
         * Busca reutilizável por outras tools (ex.: [ProjectSearchTool]).
         * Retorna linhas no formato `relpath:linha: trecho`.
         */
        fun search(
            ctx: ToolContext,
            query: String,
            filePattern: Regex?,
            maxResults: Int
        ): List<String> {
            val results = mutableListOf<String>()
            val root = ctx.projectRoot
            root.walkTopDown()
                .onEnter { dir -> dir.name !in SKIP_DIRS }
                .forEach { file ->
                    if (results.size >= maxResults) return@forEach
                    if (!file.isFile) return@forEach
                    if (file.length() > MAX_SEARCH_FILE_BYTES) return@forEach
                    if (filePattern != null && !filePattern.matches(file.name)) return@forEach
                    if (!isProbablyText(file)) return@forEach
                    try {
                        var lineNo = 0
                        file.bufferedReader().forEachLine { line ->
                            lineNo++
                            if (results.size >= maxResults) return@forEachLine
                            if (line.contains(query, ignoreCase = true)) {
                                val rel = root.toURI().relativize(file.toURI()).path
                                results.add("$rel:$lineNo: ${line.trim().take(160)}")
                            }
                        }
                    } catch (_: Exception) {
                        // Arquivo ilegível: ignora.
                    }
                }
            return results
        }

        /** Arquivos maiores que 1 MiB são pulados na busca. */
        const val MAX_SEARCH_FILE_BYTES: Long = 1024L * 1024
    }
}

// ---------------------------------------------------------------------------
// create_file
// ---------------------------------------------------------------------------

/**
 * Cria um arquivo novo com o conteúdo informado.
 * Sem confirmação (só registra no histórico); falha se o arquivo já existir.
 * Captura snapshot antes (arquivo novo = `existed=false`).
 */
class CreateFileTool : BaseTool(
    definition = ToolDefinition(
        name = "create_file",
        description = "Create a new file. Cria um arquivo novo no projeto (falha se já existir).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Caminho do novo arquivo relativo à raiz do projeto"
                },
                "content": {
                  "type": "string",
                  "description": "Conteúdo completo do arquivo"
                }
              },
              "required": ["path", "content"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val path = args.string("path") ?: return@guard ToolResult.error("Parâmetro obrigatório: path")
        val content = args.string("content") ?: return@guard ToolResult.error("Parâmetro obrigatório: content")
        val file = resolveInRoot(ctx, path)
        if (file.exists()) {
            return@guard ToolResult.error("Arquivo já existe: $path. Use edit_file para modificá-lo.")
        }
        ctx.history.captureBeforeModify(ctx.projectRoot, relativeToRoot(ctx, file))
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        ctx.history.noteFileModified(relativeToRoot(ctx, file))
        ctx.onStatus("Arquivo criado: $path")
        ToolResult.ok("Arquivo criado: ${relativeToRoot(ctx, file)} (${content.length} chars).")
    }
}

// ---------------------------------------------------------------------------
// edit_file
// ---------------------------------------------------------------------------

/**
 * Aplica substituições exatas de texto num arquivo.
 * Aceita `oldText`/`newText` ou um array `edits` (aplicados em ordem).
 * Falha com erro claro se algum `oldText` não for encontrado.
 * Verificação em duas passadas (atômico: ou aplica tudo ou nada).
 */
class EditFileTool : BaseTool(
    definition = ToolDefinition(
        name = "edit_file",
        description = "Edit a file with exact text replacement. Edita arquivo substituindo trechos exatos.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Caminho do arquivo relativo à raiz do projeto"
                },
                "oldText": {
                  "type": "string",
                  "description": "Trecho exato a ser substituído (use com newText)"
                },
                "newText": {
                  "type": "string",
                  "description": "Novo texto que substitui oldText"
                },
                "edits": {
                  "type": "array",
                  "description": "Alternativa a oldText/newText: lista de edições aplicadas em ordem",
                  "items": {
                    "type": "object",
                    "properties": {
                      "oldText": {"type": "string"},
                      "newText": {"type": "string"}
                    },
                    "required": ["oldText", "newText"]
                  }
                }
              },
              "required": ["path"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val path = args.string("path") ?: return@guard ToolResult.error("Parâmetro obrigatório: path")
        val file = resolveInRoot(ctx, path)
        if (!file.isFile) return@guard ToolResult.error("Arquivo não encontrado: $path")

        val edits = mutableListOf<Pair<String, String>>()
        val editsArray = if (args.has("edits") && args.get("edits").isJsonArray) {
            args.getAsJsonArray("edits")
        } else null
        if (editsArray != null) {
            editsArray.forEachIndexed { i, el ->
                val obj = el.asJsonObject
                val old = obj.string("oldText")
                val new = obj.string("newText")
                if (old == null || new == null) {
                    return@guard ToolResult.error("edits[$i] inválido: requer oldText e newText")
                }
                if (old.isEmpty()) {
                    return@guard ToolResult.error(
                        "edits[$i] inválido: oldText vazio corromperia o arquivo " +
                            "(\"\".replace inseriria o texto entre cada caractere). " +
                            "Informe o trecho exato a substituir."
                    )
                }
                edits.add(old to new)
            }
        } else {
            val old = args.string("oldText")
            val new = args.string("newText")
            if (old == null || new == null) {
                return@guard ToolResult.error("Informe oldText+newText ou o array edits.")
            }
            if (old.isEmpty()) {
                return@guard ToolResult.error(
                    "oldText vazio corromperia o arquivo " +
                        "(\"\".replace inseriria o texto entre cada caractere). " +
                        "Informe o trecho exato a substituir."
                )
            }
            edits.add(old to new)
        }
        if (edits.isEmpty()) return@guard ToolResult.error("Nenhuma edição informada.")

        val original = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }

        // Passada 1: verifica se todos os oldText existem (atômico).
        edits.forEachIndexed { i, (old, _) ->
            if (!original.contains(old)) {
                val preview = old.take(80).replace("\n", "\\n")
                return@guard ToolResult.error(
                    "Edição ${i + 1}/${edits.size} falhou: oldText não encontrado no arquivo.\n" +
                        "Trecho procurado (início): \"$preview\"\n" +
                        "Dica: leia o arquivo com read_file e copie o trecho exatamente."
                )
            }
        }

        // Passada 2: aplica.
        var updated = original
        var replaced = 0
        for ((old, new) in edits) {
            updated = updated.replace(old, new)
            replaced++
        }

        ctx.history.captureBeforeModify(ctx.projectRoot, relativeToRoot(ctx, file))
        withContext(Dispatchers.IO) { file.writeText(updated, Charsets.UTF_8) }
        ctx.history.noteFileModified(relativeToRoot(ctx, file))
        ctx.onStatus("Arquivo editado: $path ($replaced edição(ões))")
        ToolResult.ok("Arquivo ${relativeToRoot(ctx, file)} atualizado: $replaced edição(ões) aplicada(s).")
    }
}

// ---------------------------------------------------------------------------
// delete_file
// ---------------------------------------------------------------------------

/**
 * Deleta um arquivo do projeto. **Exige confirmação** do usuário
 * ([requiresConfirmation] = `true`) e `grants.canDelete`.
 * O conteúdo original vai para o snapshot (desfazer via `undo_changes`).
 */
class DeleteFileTool : BaseTool(
    definition = ToolDefinition(
        name = "delete_file",
        description = "Delete a file (asks confirmation). Deleta um arquivo do projeto (pede confirmação).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Caminho do arquivo relativo à raiz do projeto"
                }
              },
              "required": ["path"]
            }
        """.trimIndent()
    ),
    requiresConfirmation = true
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val path = parseArgs(argsJson).string("path")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: path")
        val file = resolveInRoot(ctx, path)
        if (!file.isFile) return@guard ToolResult.error("Arquivo não encontrado: $path")
        ctx.history.captureBeforeModify(ctx.projectRoot, relativeToRoot(ctx, file))
        val deleted = withContext(Dispatchers.IO) { file.delete() }
        if (!deleted) return@guard ToolResult.error("Não foi possível deletar: $path")
        ctx.history.noteFileModified(relativeToRoot(ctx, file))
        ctx.onStatus("Arquivo deletado: $path")
        ToolResult.ok("Arquivo deletado: ${relativeToRoot(ctx, file)}. Use undo_changes para desfazer.")
    }
}

// ---------------------------------------------------------------------------
// project_search
// ---------------------------------------------------------------------------

/**
 * Busca combinada: por nome de arquivo + por conteúdo.
 * Implementação própria simples (não depende da API do
 * [ProjectContextIndexer][dev.mutwakil.androidide.aiagent.context.ProjectContextIndexer]
 * do front-a, cujo formato ainda não está fechado — ver relatório).
 */
class ProjectSearchTool : BaseTool(
    definition = ToolDefinition(
        name = "project_search",
        description = "Search files by name and content. Busca arquivos por nome e por conteúdo no projeto.",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Termo buscado no nome dos arquivos e no conteúdo"
                }
              },
              "required": ["query"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val query = parseArgs(argsJson).string("query")?.takeIf { it.isNotBlank() }
            ?: return@guard ToolResult.error("Parâmetro obrigatório: query")
        val root = ctx.projectRoot

        val byName = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .onEnter { dir -> dir.name !in SKIP_DIRS }
                .filter { it.isFile && it.name.contains(query, ignoreCase = true) }
                .take(20)
                .map { root.toURI().relativize(it.toURI()).path }
                .toList()
        }
        val byContent = withContext(Dispatchers.IO) {
            SearchCodeTool.search(ctx, query, filePattern = null, maxResults = 30)
        }

        val sb = StringBuilder()
        sb.appendLine("Busca por '$query':")
        sb.appendLine("Arquivos por nome (${byName.size}):")
        if (byName.isEmpty()) sb.appendLine("  (nenhum)") else byName.forEach { sb.appendLine("  $it") }
        sb.appendLine("Ocorrências no conteúdo (${byContent.size}):")
        if (byContent.isEmpty()) sb.appendLine("  (nenhuma)") else byContent.forEach { sb.appendLine("  $it") }
        ToolResult.ok(sb.toString())
    }
}

/** Lista das tools de arquivo, para montagem do engine. */
fun fileTools(): List<BaseTool> = listOf(
    ReadFileTool(),
    SearchCodeTool(),
    CreateFileTool(),
    EditFileTool(),
    DeleteFileTool(),
    ProjectSearchTool()
)
