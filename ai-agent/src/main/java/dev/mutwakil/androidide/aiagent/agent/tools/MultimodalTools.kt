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

// ---------------------------------------------------------------------------
// analyze_image
// ---------------------------------------------------------------------------

/**
 * Valida um arquivo de imagem do projeto.
 *
 * A tool em si **não** interpreta a imagem: valida existência, extensão e
 * tamanho, e o [AgentEngine][dev.mutwakil.androidide.aiagent.agent.AgentEngine]
 * intercepta a chamada para reenviar a imagem ao provider **como anexo**,
 * junto com a pergunta — mas somente se o provider declarar a capability
 * `VISION` (checado via
 * [CapabilityGate][dev.mutwakil.androidide.aiagent.gate.CapabilityGate]).
 * Sem `VISION`, o engine retorna erro honesto em vez de fingir a análise.
 */
class AnalyzeImageTool : BaseTool(
    definition = ToolDefinition(
        name = "analyze_image",
        description = "Analyze an image with the AI provider (vision). Analisa uma imagem do projeto (requer visão no provider).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "imagePath": {
                  "type": "string",
                  "description": "Caminho da imagem relativo à raiz do projeto"
                },
                "question": {
                  "type": "string",
                  "description": "Pergunta sobre a imagem (ex.: 'descreva esta tela', 'há erros de layout?')"
                }
              },
              "required": ["imagePath", "question"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val imagePath = args.string("imagePath")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: imagePath")
        val question = args.string("question")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: question")
        val file = resolveInRoot(ctx, imagePath)
        if (!file.isFile) return@guard ToolResult.error("Imagem não encontrada: $imagePath")
        val ext = file.extension.lowercase()
        if (ext !in IMAGE_EXTENSIONS) {
            return@guard ToolResult.error(
                "Extensão '.$ext' não é uma imagem suportada (${IMAGE_EXTENSIONS.joinToString(", ")})."
            )
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            return@guard ToolResult.error("Imagem muito grande (${file.length()} bytes, limite ${MAX_IMAGE_BYTES}).")
        }
        ToolResult.ok(
            "IMAGE_READY path=${relativeToRoot(ctx, file)} " +
                "size=${file.length()} mime=${mimeFor(ext)} " +
                "question=$question"
        )
    }

    companion object {
        val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        /** Limite de 10 MiB por imagem. */
        const val MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024

        fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}

// ---------------------------------------------------------------------------
// analyze_document
// ---------------------------------------------------------------------------

/**
 * Extrai texto de documentos do projeto para análise.
 *
 * - Texto puro e código (`txt`, `md`, `kt`, `java`, `xml`, ...): leitura direta.
 * - **PDF: parsing não implementado.** O módulo `:ai-agent` não inclui
 *   nenhuma lib de PDF (decisão: não adicionar dependência pesada só para
 *   isso). Para PDF, a tool retorna erro honesto orientando a anexar o
 *   arquivo via `FILE_INPUT`/`PDF_INPUT` se o provider suportar — o
 *   [AgentEngine][dev.mutwakil.androidide.aiagent.agent.AgentEngine] e a UI
 *   checam isso via
 *   [CapabilityGate][dev.mutwakil.androidide.aiagent.gate.CapabilityGate].
 */
class AnalyzeDocumentTool : BaseTool(
    definition = ToolDefinition(
        name = "analyze_document",
        description = "Extract text from a document for analysis. Extrai texto de um documento (PDF não suportado).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "filePath": {
                  "type": "string",
                  "description": "Caminho do documento relativo à raiz do projeto"
                },
                "question": {
                  "type": "string",
                  "description": "Pergunta sobre o documento"
                }
              },
              "required": ["filePath", "question"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val filePath = args.string("filePath")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: filePath")
        val question = args.string("question")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: question")
        val file = resolveInRoot(ctx, filePath)
        if (!file.isFile) return@guard ToolResult.error("Documento não encontrado: $filePath")

        val ext = file.extension.lowercase()
        if (ext == "pdf") {
            return@guard ToolResult.error(
                "PDF parsing não implementado neste módulo. " +
                    "Anexe o PDF via FILE_INPUT se o provider suportar " +
                    "(verifique as capabilities do provider ativo)."
            )
        }
        if (ext in UNSUPPORTED_EXTENSIONS) {
            return@guard ToolResult.error(
                "Formato '.$ext' não suportado para extração de texto. " +
                    "Formatos de texto: ${TEXT_EXTENSIONS.joinToString(", ")}."
            )
        }
        if (file.length() > MAX_DOCUMENT_BYTES) {
            return@guard ToolResult.error("Documento muito grande (${file.length()} bytes, limite $MAX_DOCUMENT_BYTES).")
        }
        val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        ToolResult.ok(
            "Documento: ${relativeToRoot(ctx, file)} | Pergunta: $question\n---\n$text"
        )
    }

    companion object {
        val TEXT_EXTENSIONS: Set<String> = setOf(
            "txt", "md", "markdown", "kt", "kts", "java", "xml",
            "gradle", "properties", "json", "yml", "yaml", "toml",
            "cfg", "ini", "csv", "html", "htm", "css", "js", "ts",
            "py", "sh", "c", "h", "cpp", "hpp", "log"
        )
        val UNSUPPORTED_EXTENSIONS: Set<String> = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "jar", "aar", "apk", "dex", "so", "class",
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "mp4", "mp3"
        )

        /** Limite de 1 MiB por documento. */
        const val MAX_DOCUMENT_BYTES: Long = 1024L * 1024
    }
}

// ---------------------------------------------------------------------------
// compare_images
// ---------------------------------------------------------------------------

/**
 * Valida duas imagens do projeto para comparação.
 * Como em [AnalyzeImageTool], o
 * [AgentEngine][dev.mutwakil.androidide.aiagent.agent.AgentEngine] intercepta
 * a chamada e envia **ambas** ao provider como anexos com um prompt de
 * comparação — somente se houver capability `VISION`.
 */
class CompareImagesTool : BaseTool(
    definition = ToolDefinition(
        name = "compare_images",
        description = "Compare two images with the AI provider (vision). Compara duas imagens do projeto (requer visão no provider).",
        parametersSchemaJson = """
            {
              "type": "object",
              "properties": {
                "imageA": {
                  "type": "string",
                  "description": "Caminho da primeira imagem relativo à raiz do projeto"
                },
                "imageB": {
                  "type": "string",
                  "description": "Caminho da segunda imagem relativo à raiz do projeto"
                }
              },
              "required": ["imageA", "imageB"]
            }
        """.trimIndent()
    )
) {
    override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult = guard(ctx) {
        val args = parseArgs(argsJson)
        val imageA = args.string("imageA")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: imageA")
        val imageB = args.string("imageB")
            ?: return@guard ToolResult.error("Parâmetro obrigatório: imageB")
        val fileA = resolveInRoot(ctx, imageA)
        val fileB = resolveInRoot(ctx, imageB)
        if (!fileA.isFile) return@guard ToolResult.error("Imagem A não encontrada: $imageA")
        if (!fileB.isFile) return@guard ToolResult.error("Imagem B não encontrada: $imageB")
        for ((label, file) in listOf("A" to fileA, "B" to fileB)) {
            val ext = file.extension.lowercase()
            if (ext !in AnalyzeImageTool.IMAGE_EXTENSIONS) {
                return@guard ToolResult.error("Imagem $label: extensão '.$ext' não suportada.")
            }
            if (file.length() > AnalyzeImageTool.MAX_IMAGE_BYTES) {
                return@guard ToolResult.error("Imagem $label muito grande.")
            }
        }
        ToolResult.ok(
            "IMAGES_READY a=${relativeToRoot(ctx, fileA)} b=${relativeToRoot(ctx, fileB)}"
        )
    }
}

/** Lista das tools multimodais, para montagem do engine. */
fun multimodalTools(): List<BaseTool> = listOf(
    AnalyzeImageTool(),
    AnalyzeDocumentTool(),
    CompareImagesTool()
)
