package com.manichord.mgit.auth

import me.sheimi.sgit.MGitApplication
import me.sheimi.sgit.database.models.Repo
import org.eclipse.jgit.transport.URIish
import org.json.JSONObject
import timber.log.Timber

/**
 * Username/password credentials for a repo's remotes, kept per remote **host** (#87).
 *
 * The password prompt used to save one username/password per repo and apply it to every remote,
 * so a repo with, say, a GitHub and a GitLab remote kept sending one host's credentials to the
 * other (#59). Credentials entered in the prompt are now remembered for the host of the remote
 * that asked for them, both for the rest of the session and, if "save" is ticked, persistently
 * in the encrypted SecurePrefsHelper store.
 *
 * Keyed by the repo's local path rather than its id: a clone that fails authentication deletes
 * and recreates its Repo (with a new id) before retrying, and the credentials typed for it must
 * survive that. [onRepoRenamed] moves them along with a rename.
 *
 * Lookup order in [resolve]: a connected Account for the host, then this repo's credentials for
 * the host (this session's first, then saved), then the repo-level credentials saved before this
 * change, kept as a fallback so existing setups keep working until the prompt replaces them.
 */
object RepoCredentials {

    data class Credential(val username: String, val password: String)

    private const val PREF_PREFIX = "repo_credentials|"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    /** Entered in the prompt this session, keyed by [sessionKey]; not persisted. */
    private val sessionCredentials = mutableMapOf<String, Credential>()

    fun hostOf(remoteUrl: String?): String? {
        if (remoteUrl.isNullOrBlank()) return null
        return try {
            URIish(remoteUrl).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    fun resolve(repo: Repo, remoteUrl: String?): Credential? {
        val account = MGitApplication.getContext().accountManager?.findAccountForRemoteUrl(remoteUrl)
        if (account != null) {
            return credentialOrNull(account.username, account.token)
        }
        val host = hostOf(remoteUrl)
        if (host != null) {
            synchronized(sessionCredentials) { sessionCredentials[sessionKey(repo.localPath, host)] }
                ?.let { return it }
            loadSaved(repo.localPath)[host]?.let { return it }
        }
        return credentialOrNull(repo.username, repo.password)
    }

    /** Remembers credentials entered in the password prompt for [remoteUrl]'s host. */
    fun remember(repo: Repo, remoteUrl: String?, username: String, password: String, save: Boolean) {
        val host = hostOf(remoteUrl)
        val prefs = MGitApplication.getContext().securePrefsHelper
        if (host == null || prefs == null) {
            // No host to key by, or no encrypted store: fall back to the old repo-level behaviour
            repo.username = username
            repo.password = password
            if (save) repo.saveCredentials()
            return
        }
        val credential = Credential(username, password)
        synchronized(sessionCredentials) { sessionCredentials[sessionKey(repo.localPath, host)] = credential }
        if (save) {
            val saved = loadSaved(repo.localPath).toMutableMap()
            saved[host] = credential
            store(repo.localPath, saved)
        }
    }

    /** Moves a repo's credentials to its new local path after a rename. */
    fun onRepoRenamed(oldLocalPath: String, newLocalPath: String) {
        if (oldLocalPath == newLocalPath) return
        val saved = loadSaved(oldLocalPath)
        if (saved.isNotEmpty()) {
            store(newLocalPath, saved)
            store(oldLocalPath, emptyMap())
        }
        synchronized(sessionCredentials) {
            val prefix = "$oldLocalPath|"
            sessionCredentials.keys.filter { it.startsWith(prefix) }.forEach { key ->
                sessionCredentials.remove(key)?.let {
                    sessionCredentials[newLocalPath + "|" + key.removePrefix(prefix)] = it
                }
            }
        }
    }

    /** Forgets a repo's credentials; called when the user deletes the repo. */
    fun forget(localPath: String) {
        store(localPath, emptyMap())
        synchronized(sessionCredentials) {
            sessionCredentials.keys.removeAll { it.startsWith("$localPath|") }
        }
    }

    private fun sessionKey(localPath: String, host: String) = "$localPath|$host"

    private fun credentialOrNull(username: String?, password: String?): Credential? =
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) Credential(username, password) else null

    private fun loadSaved(localPath: String): Map<String, Credential> {
        val json = MGitApplication.getContext().securePrefsHelper?.get(PREF_PREFIX + localPath)
            ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { host ->
                val entry = obj.optJSONObject(host) ?: return@mapNotNull null
                credentialOrNull(entry.optString(KEY_USERNAME), entry.optString(KEY_PASSWORD))
                    ?.let { host to it }
            }.toMap()
        } catch (e: Exception) {
            Timber.w(e, "Unreadable saved credentials for %s", localPath)
            emptyMap()
        }
    }

    private fun store(localPath: String, credentials: Map<String, Credential>) {
        val prefs = MGitApplication.getContext().securePrefsHelper ?: return
        val obj = JSONObject()
        credentials.forEach { (host, credential) ->
            obj.put(host, JSONObject().put(KEY_USERNAME, credential.username).put(KEY_PASSWORD, credential.password))
        }
        prefs.set(PREF_PREFIX + localPath, obj.toString())
    }
}
