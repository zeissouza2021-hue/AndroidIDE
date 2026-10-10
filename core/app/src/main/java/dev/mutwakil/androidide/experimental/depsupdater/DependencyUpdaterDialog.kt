package dev.mutwakil.androidide.experimental.depsupdater

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import android.util.Log
import dev.mutwakil.androidide.R

class DependencyUpdaterDialog(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val buildGradleFile: File,
    private val libsVersionsTomlFile: File? = null,
    private val onDependenciesUpdated: (() -> Unit)? = null
) {
    
    private val dependencyParser = DependencyParser()
    private val updateChecker = DependencyUpdateChecker()
    private lateinit var adapter: DependencyAdapter
    private var dialog: Dialog? = null
    private var skipUntil: Long = 0
    
    fun checkForUpdates() {
        if (System.currentTimeMillis() < skipUntil) {
            Log.d("DependencyUpdaterDialog", "Skipping check, skip active until ${skipUntil}")
            scheduleNextCheck()
            return
        }
        
        lifecycleOwner.lifecycleScope.launch {
            try {
                val content = if (buildGradleFile.exists()) {
                    buildGradleFile.readText()
                } else {
                    Log.e("DependencyUpdaterDialog", "Build file not found: ${buildGradleFile.absolutePath}")
                    return@launch
                }
                
                val dependencies = dependencyParser.parseDependencies(content)
                Log.d("DependencyUpdaterDialog", "Parsed ${dependencies.size} dependencies")
                
                val versionCatalog = if (libsVersionsTomlFile?.exists() == true) {
                    dependencyParser.parseVersionCatalog(libsVersionsTomlFile.readText())
                } else {
                    emptyMap()
                }
                
                val updatedDeps = updateChecker.checkForUpdates(dependencies, versionCatalog)
                val validDeps = updatedDeps.filter { it.group.isNotEmpty() && it.name.isNotEmpty() }
                val updatesAvailable = validDeps.filter { it.hasUpdate }
                
                Log.d("DependencyUpdaterDialog", "Found ${updatesAvailable.size} updates available")
                
                if (updatesAvailable.isNotEmpty()) {
                    showUpdateDialog(validDeps)
                }
                
            } catch (e: Exception) {
                Log.e("DependencyUpdaterDialog", "Error checking updates", e)
            }
        }
    }
    
    private fun scheduleNextCheck() {
        lifecycleOwner.lifecycleScope.launch {
            val remainingTime = skipUntil - System.currentTimeMillis()
            if (remainingTime > 0) {
                delay(remainingTime)
                checkForUpdates()
            }
        }
    }
    
    private fun showUpdateDialog(dependencies: List<Dependency>) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_dependency_updates, null)
        
        val tvDialogTitle = dialogView.findViewById<MaterialTextView>(R.id.tvDialogTitle)
        val rvDependencies = dialogView.findViewById<RecyclerView>(R.id.rvDependencies)
        val btnSkip = dialogView.findViewById<MaterialButton>(R.id.btnSkip)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnClose)
        val btnUpdateAll = dialogView.findViewById<MaterialButton>(R.id.btnUpdateAll)
        
        val updatesCount = dependencies.count { it.hasUpdate }
        tvDialogTitle.text = context.getString(R.string.updates_available, "$updatesCount")
        
        rvDependencies.layoutManager = LinearLayoutManager(context)
        adapter = DependencyAdapter { dependency ->
            showWarningDialog(dependency)
        }
        rvDependencies.adapter = adapter
        adapter.submitList(dependencies)
        
        dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        btnSkip.setOnClickListener {
            skipUntil = System.currentTimeMillis() + 60000
            dialog?.dismiss()
            scheduleNextCheck()
        }
        
        btnClose.setOnClickListener {
            dialog?.dismiss()
        }
        
        btnUpdateAll.setOnClickListener {
            showWarningDialogForAll(dependencies.filter { it.hasUpdate })
        }
        
        dialog?.show()
    }
    
    private fun showWarningDialog(dependency: Dependency) {
        val warningView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
        
        val tvTitle = warningView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
        val tvMessage = warningView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
        val btnCancel = warningView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnUpdate = warningView.findViewById<MaterialButton>(R.id.btnUpdate)
        
        tvTitle.text = "⚠️ Warning"
        tvMessage.text = "You are about to update:\n\n${dependency.group}:${dependency.name}\n${dependency.currentVersion} → ${dependency.latestVersion}\n\nPlease note that newer versions may contain bugs, breaking changes, or be in alpha/beta stage. Always test thoroughly after updating.\n\nDo you want to proceed?"
        
        val warningDialog = MaterialAlertDialogBuilder(context)
            .setView(warningView)
            .setCancelable(true)
            .create()
        
        btnCancel.setOnClickListener {
            warningDialog.dismiss()
        }
        
        btnUpdate.setOnClickListener {
            warningDialog.dismiss()
            showUpdateConfirmDialog(dependency)
        }
        
        warningDialog.show()
    }
    
    private fun showWarningDialogForAll(dependencies: List<Dependency>) {
        val warningView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
        
        val tvTitle = warningView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
        val tvMessage = warningView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
        val btnCancel = warningView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnUpdate = warningView.findViewById<MaterialButton>(R.id.btnUpdate)
        
        tvTitle.text = "⚠️ Warning"
        tvMessage.text = "You are about to update ${dependencies.size} dependencies.\n\nPlease note that newer versions may contain bugs, breaking changes, or be in alpha/beta stage. Updating all dependencies at once can introduce compatibility issues.\n\nIt's recommended to update and test dependencies individually.\n\nDo you want to proceed with updating all?"
        
        val warningDialog = MaterialAlertDialogBuilder(context)
            .setView(warningView)
            .setCancelable(true)
            .create()
        
        btnCancel.setOnClickListener {
            warningDialog.dismiss()
        }
        
        btnUpdate.setOnClickListener {
            warningDialog.dismiss()
            dialog?.dismiss()
            updateAllDependencies(dependencies)
        }
        
        warningDialog.show()
    }
    
    private fun showUpdateConfirmDialog(dependency: Dependency) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
        
        val tvConfirmTitle = dialogView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
        val tvConfirmMessage = dialogView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnUpdate = dialogView.findViewById<MaterialButton>(R.id.btnUpdate)
        
        tvConfirmTitle.text = "Confirm Update"
        tvConfirmMessage.text = "${dependency.group}:${dependency.name}\n${dependency.currentVersion} → ${dependency.latestVersion}\n\nProceed with update?"
        
        val confirmDialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        btnCancel.setOnClickListener {
            confirmDialog.dismiss()
        }
        
        btnUpdate.setOnClickListener {
            confirmDialog.dismiss()
            updateDependency(dependency)
        }
        
        confirmDialog.show()
    }
    
    private fun updateDependency(dependency: Dependency) {
        try {
            requireLatestVersion(dependency)
            when {
                dependency.catalogReference != null -> {
                    updateInVersionCatalog(dependency)
                }
                dependency.variableReference != null -> {
                    updateVariableInBuildGradle(dependency)
                }
                else -> {
                    updateDirectInBuildGradle(dependency)
                }
            }
            
            val successView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
            val tvTitle = successView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
            val tvMessage = successView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
            val btnCancel = successView.findViewById<MaterialButton>(R.id.btnCancel)
            val btnUpdate = successView.findViewById<MaterialButton>(R.id.btnUpdate)
            
            tvTitle.text = "Success"
            tvMessage.text = "${dependency.group}:${dependency.name} updated to ${dependency.latestVersion}"
            btnCancel.visibility = View.GONE
            btnUpdate.text = "OK"
            
            val successDialog = MaterialAlertDialogBuilder(context)
                .setView(successView)
                .setCancelable(true)
                .create()
            
            btnUpdate.setOnClickListener {
                successDialog.dismiss()
                onDependenciesUpdated?.invoke()
                dialog?.dismiss()
                checkForUpdates()
            }
            
            successDialog.show()
                
        } catch (e: Exception) {
            Log.e("DependencyUpdaterDialog", "Error updating dependency", e)
            
            val errorView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
            val tvTitle = errorView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
            val tvMessage = errorView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
            val btnCancel = errorView.findViewById<MaterialButton>(R.id.btnCancel)
            val btnUpdate = errorView.findViewById<MaterialButton>(R.id.btnUpdate)
            
            tvTitle.text = "Error"
            tvMessage.text = "Failed to update: ${e.message}"
            btnCancel.visibility = View.GONE
            btnUpdate.text = "OK"
            
            val errorDialog = MaterialAlertDialogBuilder(context)
                .setView(errorView)
                .setCancelable(true)
                .create()
            
            btnUpdate.setOnClickListener {
                errorDialog.dismiss()
            }
            
            errorDialog.show()
        }
    }
    
    private fun updateAllDependencies(dependencies: List<Dependency>) {
        val progressView = LayoutInflater.from(context).inflate(R.layout.dialog_deps_progress, null)
        
        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(progressView)
            .setCancelable(false)
            .create()
        
        progressDialog.show()
        
        lifecycleOwner.lifecycleScope.launch {
            try {
                var successCount = 0
                var failCount = 0
                
                dependencies.forEach { dependency ->
                    try {
                        requireLatestVersion(dependency)
                        when {
                            dependency.catalogReference != null -> {
                                updateInVersionCatalog(dependency)
                            }
                            dependency.variableReference != null -> {
                                updateVariableInBuildGradle(dependency)
                            }
                            else -> {
                                updateDirectInBuildGradle(dependency)
                            }
                        }
                        successCount++
                    } catch (e: Exception) {
                        Log.e("DependencyUpdaterDialog", "Error updating ${dependency.name}", e)
                        failCount++
                    }
                }
                
                progressDialog.dismiss()
                
                val resultView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
                val tvTitle = resultView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
                val tvMessage = resultView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
                val btnCancel = resultView.findViewById<MaterialButton>(R.id.btnCancel)
                val btnUpdate = resultView.findViewById<MaterialButton>(R.id.btnUpdate)
                
                tvTitle.text = "Update Complete"
                tvMessage.text = "Successfully updated: $successCount\nFailed: $failCount"
                btnCancel.visibility = View.GONE
                btnUpdate.text = "OK"
                
                val resultDialog = MaterialAlertDialogBuilder(context)
                    .setView(resultView)
                    .setCancelable(true)
                    .create()
                
                btnUpdate.setOnClickListener {
                    resultDialog.dismiss()
                    onDependenciesUpdated?.invoke()
                    dialog?.dismiss()
                }
                
                resultDialog.show()
                    
            } catch (e: Exception) {
                progressDialog.dismiss()
                
                val errorView = LayoutInflater.from(context).inflate(R.layout.dialog_update_confirm, null)
                val tvTitle = errorView.findViewById<MaterialTextView>(R.id.tvConfirmTitle)
                val tvMessage = errorView.findViewById<MaterialTextView>(R.id.tvConfirmMessage)
                val btnCancel = errorView.findViewById<MaterialButton>(R.id.btnCancel)
                val btnUpdate = errorView.findViewById<MaterialButton>(R.id.btnUpdate)
                
                tvTitle.text = "Error"
                tvMessage.text = "Failed to update dependencies: ${e.message}"
                btnCancel.visibility = View.GONE
                btnUpdate.text = "OK"
                
                val errorDialog = MaterialAlertDialogBuilder(context)
                    .setView(errorView)
                    .setCancelable(true)
                    .create()
                
                btnUpdate.setOnClickListener {
                    errorDialog.dismiss()
                }
                
                errorDialog.show()
            }
        }
    }
    
    private fun updateInVersionCatalog(dependency: Dependency) {
        val catalogFile = libsVersionsTomlFile
            ?.takeIf { it.isFile }
            ?: throw IllegalStateException("libs.versions.toml not found")
        val latestVersion = requireLatestVersion(dependency)
        val original = catalogFile.readText()
        val normalizedRef = dependency.catalogReference
            ?.replace(".", "-")
            ?: throw IllegalStateException("Missing version-catalog alias")

        // Parse the library declaration by its actual coordinates instead of
        // assuming a fixed TOML property order or a one-line declaration.
        val versionRefPattern = Regex(
            """([a-zA-Z0-9_-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"\s*,\s*name\s*=\s*"([^"]+)"\s*,\s*version\.ref\s*=\s*"([^"]+)"""
        )
        val versionRefMatch = versionRefPattern.find(original)?.takeIf {
            it.groupValues[2] == dependency.group && it.groupValues[3] == dependency.name
        }

        val updated = if (versionRefMatch != null) {
            val versionKey = versionRefMatch.groupValues[4]
            val versionPattern = Regex(
                """(?m)^([ \t]*""" + Regex.escape(versionKey) + """[ \t]*=[ \t]*")([^"]+)(".*)$"""
            )
            if (!versionPattern.containsMatchIn(original)) {
                throw IllegalStateException("Version key '" + versionKey + "' was not found in [versions]")
            }
            versionPattern.replaceFirst(original) {
                it.groupValues[1] + latestVersion + it.groupValues[3]
            }
        } else {
            val directPattern = Regex(
                """(?m)^([ \t]*""" + Regex.escape(normalizedRef) + """[ \t]*=[ \t]*")([^:"]+):([^:"]+):([^"]+)(".*)$"""
            )
            if (!directPattern.containsMatchIn(original)) {
                throw IllegalStateException(
                    "Could not find catalog entry '" + normalizedRef + "'; no file was changed"
                )
            }
            directPattern.replaceFirst(original) {
                it.groupValues[1] + it.groupValues[2] + ":" + it.groupValues[3] +
                    ":" + latestVersion + it.groupValues[5]
            }
        }

        writeTextWithBackup(catalogFile, original, updated)
        Log.d("DependencyUpdaterDialog", "Updated libs.versions.toml")
    }

    private fun updateVariableInBuildGradle(dependency: Dependency) {
        if (!buildGradleFile.isFile) {
            throw IllegalStateException("Build file not found: " + buildGradleFile.absolutePath)
        }
        val variable = dependency.variableReference
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing version variable")
        val latestVersion = requireLatestVersion(dependency)
        val original = buildGradleFile.readText()
        val pattern = Regex(
            """(?m)^([ \t]*(?:val|var)[ \t]+""" + Regex.escape(variable) + """[ \t]*=[ \t]*["'])([^"']+)(["'])"""
        )
        if (!pattern.containsMatchIn(original)) {
            throw IllegalStateException(
                "Could not find version variable '" + variable + "'; no file was changed"
            )
        }
        val updated = pattern.replaceFirst(original) {
            it.groupValues[1] + latestVersion + it.groupValues[3]
        }
        writeTextWithBackup(buildGradleFile, original, updated)
        Log.d("DependencyUpdaterDialog", "Updated variable " + variable + " in build.gradle")
    }

    private fun updateDirectInBuildGradle(dependency: Dependency) {
        if (!buildGradleFile.isFile) {
            throw IllegalStateException("Build file not found: " + buildGradleFile.absolutePath)
        }
        val latestVersion = requireLatestVersion(dependency)
        val original = buildGradleFile.readText()
        val oldCoordinate = dependency.group + ":" + dependency.name + ":" + dependency.currentVersion
        if (!original.contains(oldCoordinate)) {
            throw IllegalStateException(
                "Could not find dependency '" + oldCoordinate + "'; no file was changed"
            )
        }
        val updated = original.replace(
            oldCoordinate,
            dependency.group + ":" + dependency.name + ":" + latestVersion
        )
        writeTextWithBackup(buildGradleFile, original, updated)
        Log.d("DependencyUpdaterDialog", "Updated direct dependency in build.gradle")
    }

    private fun requireLatestVersion(dependency: Dependency): String {
        val version = dependency.latestVersion?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("Error:", ignoreCase = true) }
            ?: throw IllegalStateException(
                "Latest version is unavailable for " + dependency.group + ":" + dependency.name
            )
        if (!dependency.hasUpdate || version == dependency.currentVersion) {
            throw IllegalStateException(
                "No confirmed version update is available for " + dependency.group + ":" + dependency.name
            )
        }
        return version
    }

    /**
     * Writes only changed content, creates a project-local backup first, and
     * restores the original file if replacing it fails.
     */
    private fun writeTextWithBackup(file: File, original: String, updated: String) {
        if (original == updated) {
            throw IllegalStateException("The dependency entry was not changed; the file was left untouched")
        }
        val parent = file.parentFile
            ?: throw IllegalStateException("Cannot determine parent directory for " + file.name)
        val backupDir = File(parent, ".androidide-backups")
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw IllegalStateException("Could not create backup directory: " + backupDir.absolutePath)
        }
        val backup = File(
            backupDir,
            file.name + "." + System.currentTimeMillis() + "-" + System.nanoTime() + ".bak"
        )
        file.copyTo(backup, overwrite = false)
        val temporary = File.createTempFile("deps-", ".tmp", parent)
        try {
            temporary.writeText(updated)
            if (!temporary.renameTo(file)) {
                file.writeText(updated)
            }
            if (file.readText() != updated) {
                throw IllegalStateException("Updated file verification failed")
            }
        } catch (e: Exception) {
            runCatching { backup.copyTo(file, overwrite = true) }
            throw e
        } finally {
            temporary.delete()
        }
    }
}