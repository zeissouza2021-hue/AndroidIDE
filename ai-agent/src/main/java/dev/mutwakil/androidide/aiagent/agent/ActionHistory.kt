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

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Histórico de ações do agente + snapshots para `undo_changes`.
 *
 * ## Decisão de design: snapshots via cópia de arquivos (zip)
 * O ARCHITECTURE.md sugeria JGit via `:subprojects:git-core` quando `.git`
 * existir. Optamos por **não** usar JGit aqui:
 * - adicionaria uma dependência pesada ao módulo `:ai-agent`;
 * - só funcionaria em projetos que são repos git (muitos projetos no
 *   AndroidIDE não são, ou têm `.git` parcial);
 * - a semântica de "desfazer o que o agente fez" é mais simples e previsível
 *   com cópia direta dos arquivos tocados.
 *
 * Em vez disso, o snapshot é **preguiçoso (lazy)**: o [AgentEngine] chama
 * [createSnapshot] antes da fase de modificações, e cada tool de escrita
 * ([create_file][dev.mutwakil.androidide.aiagent.agent.tools.CreateFileTool],
 * [edit_file][dev.mutwakil.androidide.aiagent.agent.tools.EditFileTool],
 * [delete_file][dev.mutwakil.androidide.aiagent.agent.tools.DeleteFileTool])
 * chama [captureBeforeModify] **antes** de alterar cada arquivo. Só o estado
 * original dos arquivos realmente tocados é guardado — rápido e leve.
 *
 * Os snapshots vão para `<cacheDir>/aiagent_snapshots/<id>.zip` (com
 * `manifest.json` interno registrando quais arquivos existiam ou não, para
 * que `undo_changes` também recrie arquivos deletados e remova arquivos
 * criados pelo agente). Se [cacheDir] for `null`, tudo fica só em memória.
 *
 * O histórico de [ActionRecord] é persistido em JSON
 * (`<cacheDir>/aiagent/history.json`); os snapshots, em `snapshots.json`
 * (metadados; os zips permanecem no disco).
 */
class ActionHistory(
    private val cacheDir: File? = null
) {

    /** Um registro de execução completa do agente. */
    data class ActionRecord(
        val timestamp: Long,
        val userCommand: String,
        val plan: List<String>,
        val filesModified: List<String>,
        val actionsPerformed: List<String>,
        val result: String
    )

    /** Uma entrada de arquivo dentro de um snapshot. */
    data class SnapshotEntry(
        /** Caminho relativo à raiz do projeto, com `/` como separador. */
        val relativePath: String,
        /** `false` se o arquivo NÃO existia antes (foi criado pelo agente). */
        val existed: Boolean
    )

    /**
     * Snapshot do estado original dos arquivos tocados numa execução.
     * O conteúdo fica no [zipFile] (disco) ou em [blobs] (memória).
     */
    data class Snapshot(
        val id: String,
        val timestamp: Long = System.currentTimeMillis(),
        val entries: List<SnapshotEntry> = emptyList(),
        internal val zipFile: File? = null,
        internal val blobs: Map<String, ByteArray?> = emptyMap()
    )

    private val gson: Gson = GsonBuilder().create()
    private val records = mutableListOf<ActionRecord>()
    private val snapshots = mutableListOf<Snapshot>()
    private val pendingModified = mutableListOf<String>()

    /** Raiz do projeto do snapshot corrente (para [restoreSnapshot]). */
    private var lastProjectRoot: File? = null

    /** Builder do snapshot em construção (captura preguiçosa). */
    private var openBuilder: SnapshotBuilder? = null

    private val historyFile: File? get() = cacheDir?.let { File(it, "aiagent/history.json") }
    private val snapshotsDir: File? get() = cacheDir?.let { File(it, "aiagent_snapshots") }
    private val snapshotsMetaFile: File? get() = cacheDir?.let { File(it, "aiagent/snapshots.json") }

    init {
        loadPersisted()
    }

    // ------------------------------------------------------------------
    // Histórico
    // ------------------------------------------------------------------

    /**
     * Registra uma execução completa do agente.
     * Consome também os arquivos acumulados via [noteFileModified].
     */
    @Synchronized
    fun record(
        userCommand: String,
        plan: List<String> = emptyList(),
        filesModified: List<String> = emptyList(),
        actionsPerformed: List<String> = emptyList(),
        result: String = ""
    ): ActionRecord {
        val merged = (filesModified + takePendingModified()).distinct()
        val record = ActionRecord(
            timestamp = System.currentTimeMillis(),
            userCommand = userCommand,
            plan = plan,
            filesModified = merged,
            actionsPerformed = actionsPerformed,
            result = result
        )
        records.add(record)
        persistHistory()
        return record
    }

    /** Lista todos os registros, do mais antigo ao mais recente. */
    @Synchronized
    fun list(): List<ActionRecord> = records.toList()

    /**
     * Anota que um arquivo foi modificado na execução corrente.
     * Chamado pelas tools de escrita; o [AgentEngine] drena via
     * [takePendingModified] após cada tool.
     */
    @Synchronized
    fun noteFileModified(relativePath: String) {
        val normalized = relativePath.replace(File.separatorChar, '/')
        if (!pendingModified.contains(normalized)) pendingModified.add(normalized)
    }

    /** Drena e retorna os arquivos modificados pendentes. */
    @Synchronized
    fun takePendingModified(): List<String> {
        val copy = pendingModified.toList()
        pendingModified.clear()
        return copy
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    /**
     * Inicia um novo snapshot para [projectRoot].
     * A captura do conteúdo é preguiçosa: acontece em [captureBeforeModify].
     * Se já houver um snapshot aberto, ele é finalizado antes.
     */
    @Synchronized
    fun createSnapshot(projectRoot: File): Snapshot {
        closeCurrentSnapshot()
        lastProjectRoot = projectRoot
        val id = "snapshot_${System.currentTimeMillis()}"
        openBuilder = SnapshotBuilder(id, projectRoot, snapshotsDir)
        // Retorna um handle preliminar; a versão final (com entries) é
        // publicada em [snapshots] ao fechar o builder.
        return Snapshot(id = id)
    }

    /**
     * Captura o estado atual de [relativePath] no snapshot aberto,
     * **antes** de ser modificado. Chamado pelas tools de escrita.
     * Arquivos já capturados são ignorados (vale o estado original).
     */
    @Synchronized
    fun captureBeforeModify(projectRoot: File, relativePath: String) {
        var builder = openBuilder
        if (builder == null) {
            // Uso fora do fluxo do engine: abre um snapshot implícito.
            lastProjectRoot = projectRoot
            builder = SnapshotBuilder("snapshot_${System.currentTimeMillis()}", projectRoot, snapshotsDir)
            openBuilder = builder
        }
        try {
            builder.capture(relativePath.replace(File.separatorChar, '/'))
        } catch (_: Exception) {
            // Snapshot é best-effort: nunca deve quebrar a tool.
        }
    }

    /** Finaliza o snapshot aberto, publicando-o em [snapshots]. */
    @Synchronized
    fun closeCurrentSnapshot() {
        val builder = openBuilder ?: return
        openBuilder = null
        try {
            val snapshot = builder.finish()
            if (snapshot.entries.isNotEmpty()) {
                snapshots.add(snapshot)
                persistSnapshotsMeta()
            } else {
                // Snapshot vazio: descarta o zip.
                snapshot.zipFile?.delete()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    /** Retorna o snapshot mais recente, ou `null` se não houver. */
    @Synchronized
    fun latestSnapshot(): Snapshot? = snapshots.lastOrNull()

    /**
     * Restaura [snapshot]: arquivos que existiam voltam ao conteúdo original
     * (recriados se foram deletados); arquivos criados pelo agente são removidos.
     *
     * @throws IllegalStateException se não houver raiz de projeto conhecida.
     */
    @Synchronized
    fun restoreSnapshot(snapshot: Snapshot) {
        closeCurrentSnapshot()
        val root = lastProjectRoot
            ?: throw IllegalStateException("Nenhum projeto associado ao snapshot ${snapshot.id}")
        val blobs: Map<String, ByteArray?> = if (snapshot.zipFile != null && snapshot.zipFile.exists()) {
            readZipBlobs(snapshot.zipFile)
        } else {
            snapshot.blobs
        }
        for (entry in snapshot.entries) {
            val target = File(root, entry.relativePath)
            // Trava anti path-traversal: o snapshot é persistido em disco e
            // pode ser adulterado — um "../" aqui escreveria fora do projeto.
            val canonicalRoot = root.canonicalPath
            val canonicalTarget = target.canonicalPath
            if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) {
                throw IllegalStateException(
                    "Snapshot contém caminho fora do projeto: ${entry.relativePath}"
                )
            }
            if (entry.existed) {
                val bytes = blobs[entry.relativePath]
                    ?: throw IllegalStateException("Conteúdo ausente no snapshot para ${entry.relativePath}")
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            } else {
                if (target.exists()) target.delete()
            }
        }
    }

    // ------------------------------------------------------------------
    // Builder interno (captura preguiçosa)
    // ------------------------------------------------------------------

    private class SnapshotBuilder(
        val id: String,
        private val projectRoot: File,
        snapshotsDir: File?
    ) {
        private val entries = mutableListOf<SnapshotEntry>()
        private val captured = mutableSetOf<String>()
        private val zipFile: File? = snapshotsDir?.let {
            it.mkdirs()
            File(it, "$id.zip")
        }
        private val zipOut: ZipOutputStream? = zipFile?.let { ZipOutputStream(it.outputStream().buffered()) }
        private val memoryBlobs = mutableMapOf<String, ByteArray?>()
        private var finished = false

        fun capture(relativePath: String) {
            if (finished || !captured.add(relativePath)) return
            val file = File(projectRoot, relativePath)
            val existed = file.isFile
            val bytes: ByteArray? = if (existed) {
                // Trava de segurança: não snapshotar arquivos gigantes.
                if (file.length() > MAX_SNAPSHOT_FILE_BYTES) {
                    captured.remove(relativePath)
                    return
                }
                file.readBytes()
            } else {
                null
            }
            entries.add(SnapshotEntry(relativePath, existed))
            if (zipOut != null && bytes != null) {
                zipOut.putNextEntry(ZipEntry("files/$relativePath"))
                zipOut.write(bytes)
                zipOut.closeEntry()
            } else if (bytes != null) {
                memoryBlobs[relativePath] = bytes
            }
        }

        fun finish(): Snapshot {
            finished = true
            // Manifesto como última entrada do zip.
            val manifest = mapOf(
                "id" to id,
                "entries" to entries.map { mapOf("path" to it.relativePath, "existed" to it.existed) }
            )
            val manifestJson = Gson().toJson(manifest)
            if (zipOut != null) {
                zipOut.putNextEntry(ZipEntry("manifest.json"))
                zipOut.write(manifestJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                zipOut.close()
            }
            return Snapshot(
                id = id,
                entries = entries.toList(),
                zipFile = if (zipOut != null) zipFile else null,
                blobs = memoryBlobs.toMap()
            )
        }
    }

    private fun readZipBlobs(zipFile: File): Map<String, ByteArray?> {
        val result = mutableMapOf<String, ByteArray?>()
        ZipFile(zipFile).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json") ?: return emptyMap()
            val manifestJson = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val manifest: Map<String, Any?> = gson.fromJson(
                manifestJson, object : TypeToken<Map<String, Any?>>() {}.type
            )
            @Suppress("UNCHECKED_CAST")
            val entries = manifest["entries"] as? List<Map<String, Any?>> ?: emptyList()
            for (e in entries) {
                val path = e["path"] as? String ?: continue
                val existed = e["existed"] as? Boolean ?: true
                result[path] = if (existed) {
                    zip.getEntry("files/$path")?.let { zip.getInputStream(it).readBytes() }
                } else {
                    null
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // Persistência
    // ------------------------------------------------------------------

    private fun persistHistory() {
        val file = historyFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(records))
        } catch (_: Exception) {
        }
    }

    private fun persistSnapshotsMeta() {
        val file = snapshotsMetaFile ?: return
        try {
            file.parentFile?.mkdirs()
            // Só snapshots em disco são persistidos: os só-em-memória (zipFile == null)
            // não teriam os blobs após reiniciar o app.
            val meta = snapshots.filter { it.zipFile != null }.map {
                mapOf(
                    "id" to it.id,
                    "timestamp" to it.timestamp,
                    "zipPath" to it.zipFile?.absolutePath,
                    "entries" to it.entries.map { e -> mapOf("path" to e.relativePath, "existed" to e.existed) }
                )
            }
            file.writeText(gson.toJson(meta))
        } catch (_: Exception) {
        }
    }

    private fun loadPersisted() {
        try {
            historyFile?.takeIf { it.isFile }?.let { file ->
                val type = object : TypeToken<List<ActionRecord>>() {}.type
                val loaded: List<ActionRecord>? = gson.fromJson(file.readText(), type)
                loaded?.let { records.addAll(it) }
            }
            snapshotsMetaFile?.takeIf { it.isFile }?.let { file ->
                val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
                val meta: List<Map<String, Any?>>? = gson.fromJson(file.readText(), type)
                meta?.forEach { m ->
                    val id = m["id"] as? String ?: return@forEach
                    val timestamp = (m["timestamp"] as? Number)?.toLong() ?: 0L
                    val zipPath = m["zipPath"] as? String
                    @Suppress("UNCHECKED_CAST")
                    val entriesRaw = m["entries"] as? List<Map<String, Any?>> ?: emptyList()
                    val entries = entriesRaw.mapNotNull { e ->
                        val path = e["path"] as? String ?: return@mapNotNull null
                        SnapshotEntry(path, e["existed"] as? Boolean ?: true)
                    }
                    val zipFile = zipPath?.let { File(it) }?.takeIf { it.isFile }
                    // Só restaura metadados cujo zip ainda existe no disco.
                    if (zipFile != null) {
                        snapshots.add(Snapshot(id, timestamp, entries, zipFile))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Arquivos maiores que isso não entram no snapshot (8 MiB). */
        const val MAX_SNAPSHOT_FILE_BYTES: Long = 8L * 1024 * 1024
    }
}
