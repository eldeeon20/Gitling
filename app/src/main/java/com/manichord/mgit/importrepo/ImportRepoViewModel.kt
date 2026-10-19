package com.manichord.mgit.importrepo

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.sheimi.sgit.MGitApplication
import me.sheimi.sgit.R
import me.sheimi.sgit.database.RepoContract
import me.sheimi.sgit.database.models.Repo
import timber.log.Timber

/** Drives the import flow (#57): explain what importing does, pick a folder or ZIP file, name
 * the repo, copy it with progress, then report what was done. Activity-scoped, so an import keeps
 * running across configuration changes. */
class ImportRepoViewModel(application: Application) : AndroidViewModel(application) {

    sealed class State {
        object Hidden : State()
        /** What importing does and its limits, with the folder / ZIP choice. */
        object Intro : State()
        data class Naming(val source: Uri, val isZip: Boolean, val name: String, val error: String?) : State()
        data class Importing(val name: String, val isZip: Boolean, val done: Long, val total: Long) : State()
        data class Done(val repo: Repo, val result: RepoImporter.Result) : State()
        data class Failed(val problem: RepoImporter.Problem, val detail: String?) : State()
    }

    private val _state = MutableStateFlow<State>(State.Hidden)
    val state: StateFlow<State> = _state.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    fun start() {
        _state.value = State.Intro
    }

    fun dismiss() {
        if (_state.value !is State.Importing) _state.value = State.Hidden
    }

    /** The picker returned [uri], or null if the user backed out of it. */
    fun onSourcePicked(uri: Uri?, isZip: Boolean) {
        if (uri == null) {
            _state.value = State.Intro
            return
        }
        _state.value = State.Hidden
        Thread {
            // A folder can be checked before asking for a name; a ZIP only as it's extracted
            try {
                if (!isZip) RepoImporter.checkFolder(getApplication(), uri)
                val name = RepoImporter.suggestedName(getApplication(), uri, isZip)
                _state.value = State.Naming(uri, isZip, name, validateName(name))
            } catch (e: RepoImporter.ImportException) {
                _state.value = State.Failed(e.problem, e.detail)
            } catch (e: Exception) {
                Timber.e(e, "Couldn't read the picked folder")
                _state.value = State.Failed(RepoImporter.Problem.READ_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    fun onNameChanged(name: String) {
        val naming = _state.value as? State.Naming ?: return
        _state.value = naming.copy(name = name, error = validateName(name))
    }

    fun startImport() {
        val naming = _state.value as? State.Naming ?: return
        val name = naming.name.trim()
        val error = validateName(name)
        if (error != null) {
            _state.value = naming.copy(error = error)
            return
        }
        val app = getApplication<MGitApplication>()
        val destDir = Repo.getDir(app.prefenceHelper, name)
        cancelled.set(false)
        _state.value = State.Importing(name, naming.isZip, 0, -1)
        Thread {
            var lastUpdate = 0L
            val progress = RepoImporter.ProgressListener { done, total ->
                // Every 64 KB is far more often than the screen can show
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 100) {
                    lastUpdate = now
                    _state.value = State.Importing(name, naming.isZip, done, total)
                }
            }
            try {
                RepoImporter.cleanUpStaleImports(destDir.parentFile!!)
                val result = if (naming.isZip) {
                    RepoImporter.importZip(app, naming.source, destDir, progress) { cancelled.get() }
                } else {
                    RepoImporter.importFolder(app, naming.source, destDir, progress) { cancelled.get() }
                }
                _state.value = State.Done(register(name), result)
            } catch (e: RepoImporter.ImportException) {
                Timber.w(e, "Import of %s failed: %s", name, e.problem)
                _state.value = if (e.problem == RepoImporter.Problem.CANCELLED) {
                    State.Failed(e.problem, null)
                } else {
                    State.Failed(e.problem, e.detail)
                }
            } catch (e: Exception) {
                Timber.e(e, "Import of %s failed", name)
                _state.value = State.Failed(RepoImporter.Problem.READ_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    fun cancelImport() {
        cancelled.set(true)
    }

    /** Adds the copied repo to the list, with its remote and last commit filled in from the
     * repo itself so its card isn't blank. */
    private fun register(name: String): Repo {
        val repo = Repo.createRepo(name, LOCAL_REPOSITORY, RepoContract.REPO_STATUS_NULL)
        if (repo.remoteOriginURL.isNotEmpty()) {
            repo.updateRemote()
        }
        repo.updateLatestCommitInfo()
        return Repo.getRepoById(repo.getID().toLong())
    }

    /** Same rules as a clone's local name (CloneViewModel.validateLocalName). */
    private fun validateName(name: String): String? {
        val app = getApplication<MGitApplication>()
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> app.getString(R.string.alert_localpath_required)
            trimmed.contains("/") -> app.getString(R.string.alert_localpath_format)
            trimmed.startsWith(".") -> app.getString(R.string.alert_import_name_dot)
            Repo.getDir(app.prefenceHelper, trimmed).exists() -> app.getString(R.string.alert_localpath_repo_exists)
            else -> null
        }
    }

    /** Where an import with [name] would go, for display. */
    fun destinationFor(name: String): File =
        Repo.getDir(getApplication<MGitApplication>().prefenceHelper, name.trim())

    companion object {
        /** What CloneViewModel.initLocalRepo stores as the remote of a repo without one. */
        private const val LOCAL_REPOSITORY = "local repository"
    }
}
