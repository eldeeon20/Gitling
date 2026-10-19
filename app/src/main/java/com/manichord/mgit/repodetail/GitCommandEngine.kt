package com.manichord.mgit.repodetail

import com.manichord.mgit.auth.RepoCredentials
import me.sheimi.sgit.database.models.Repo
import me.sheimi.sgit.ssh.SgitTransportCallback
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.submodule.SubmoduleStatusType
import org.eclipse.jgit.submodule.SubmoduleWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GitCommandEngine {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun execute(repo: Repo, input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val cmd = trimmed.removePrefix("git ").trim()
        val parts = tokenize(cmd)
        if (parts.isEmpty()) return ""
        return try {
            val git = repo.getGit()
            when (parts[0]) {
                "status" -> runStatus(repo)
                "log" -> runLog(repo, parts.drop(1))
                "diff" -> runDiff(repo, parts.drop(1))
                "branch" -> runBranch(repo, parts.drop(1))
                "stash" -> runStash(repo, parts.drop(1))
                "fetch" -> runFetch(repo, parts.drop(1))
                "pull" -> runPull(repo, parts.drop(1))
                "submodule" -> runSubmodule(repo, parts.drop(1))
                "help", "--help", "-h" -> helpText()
                else -> "unknown command: ${parts[0]}\n\n${helpText()}"
            }
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun runStatus(repo: Repo): String {
        val git = repo.getGit()
        val branchName = git.repository.branch ?: "HEAD"
        val status = git.status().call()

        val sb = StringBuilder()
        sb.appendLine("On branch $branchName")

        val staged = status.added + status.changed + status.removed
        if (staged.isNotEmpty()) {
            sb.appendLine("\nChanges to be committed:")
            status.added.sorted().forEach { sb.appendLine("        new file:   $it") }
            status.changed.sorted().forEach { sb.appendLine("        modified:   $it") }
            status.removed.sorted().forEach { sb.appendLine("        deleted:    $it") }
        }

        val unstaged = status.modified + status.missing
        if (unstaged.isNotEmpty()) {
            sb.appendLine("\nChanges not staged for commit:")
            status.modified.sorted().forEach { sb.appendLine("        modified:   $it") }
            status.missing.sorted().forEach { sb.appendLine("        deleted:    $it") }
        }

        if (status.untracked.isNotEmpty()) {
            sb.appendLine("\nUntracked files:")
            status.untracked.sorted().forEach { sb.appendLine("        $it") }
        }

        if (staged.isEmpty() && unstaged.isEmpty() && status.untracked.isEmpty()) {
            sb.append("nothing to commit, working tree clean")
        }
        return sb.toString().trimEnd()
    }

    private fun runLog(repo: Repo, args: List<String>): String {
        val git = repo.getGit()
        val oneline = "--oneline" in args
        val graph = "--graph" in args
        val limit = args.firstOrNull { it.startsWith("-") && it.drop(1).all(Char::isDigit) }
            ?.drop(1)?.toIntOrNull()
            ?: args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
            ?: 20
        val since = args.firstOrNull { it.startsWith("--since=") }
            ?.removePrefix("--since=")?.let { parseSinceDate(it) }

        val logCmd = git.log().setMaxCount(if (since != null) 500 else limit)
        val commits = logCmd.call().toList()

        val filtered = if (since != null) {
            commits.filter { it.commitTime.toLong() * 1000 >= since }.take(limit)
        } else commits

        if (filtered.isEmpty()) return "(no commits)"

        val sb = StringBuilder()
        val prefix = if (graph) "* " else ""
        filtered.forEach { commit ->
            val sha = commit.name.take(7)
            val date = dateFormat.format(Date(commit.commitTime.toLong() * 1000))
            val author = commit.authorIdent.name
            val msg = commit.shortMessage
            if (oneline) {
                sb.appendLine("$prefix$sha $msg")
            } else {
                sb.appendLine("${prefix}commit ${commit.name}")
                sb.appendLine("Author: $author")
                sb.appendLine("Date:   $date")
                sb.appendLine()
                sb.appendLine("    $msg")
                sb.appendLine()
            }
        }
        return sb.toString().trimEnd()
    }

    private fun runDiff(repo: Repo, args: List<String>): String {
        val git = repo.getGit()
        val staged = "--staged" in args || "--cached" in args
        val out = ByteArrayOutputStream()
        val formatter = DiffFormatter(out).apply {
            setRepository(git.repository)
            setDiffComparator(RawTextComparator.DEFAULT)
            isDetectRenames = true
        }
        val diffs = git.diff().setCached(staged).call()
        if (diffs.isEmpty()) return if (staged) "(no staged changes)" else "(no unstaged changes)"

        val sb = StringBuilder()
        diffs.take(20).forEach { entry ->
            sb.appendLine("diff --git a/${entry.oldPath} b/${entry.newPath}")
            sb.appendLine("--- a/${entry.oldPath}")
            sb.appendLine("+++ b/${entry.newPath}")
            try {
                out.reset()
                formatter.format(entry)
                val patch = out.toString()
                val hunks = patch.lines().filter { it.startsWith("@@") || it.startsWith("+") || it.startsWith("-") }
                hunks.take(30).forEach { sb.appendLine(it) }
                if (hunks.size > 30) sb.appendLine("... (${hunks.size - 30} more lines)")
            } catch (_: Exception) { }
            sb.appendLine()
        }
        if (diffs.size > 20) sb.appendLine("... and ${diffs.size - 20} more file(s)")
        formatter.close()
        return sb.toString().trimEnd()
    }

    private fun runBranch(repo: Repo, args: List<String>): String {
        val git = repo.getGit()
        val mode = when {
            "-a" in args -> ListBranchCommand.ListMode.ALL
            "-r" in args -> ListBranchCommand.ListMode.REMOTE
            else -> null
        }
        val current = git.repository.branch
        val branches = git.branchList().apply { mode?.let { setListMode(it) } }.call()
        if (branches.isEmpty()) return "(no branches)"
        val sb = StringBuilder()
        branches.forEach { ref ->
            val name = ref.name
                .removePrefix("refs/heads/")
                .removePrefix("refs/remotes/")
            val marker = if (ref.name == "refs/heads/$current") "* " else "  "
            sb.appendLine("$marker$name")
        }
        return sb.toString().trimEnd()
    }

    private fun runStash(repo: Repo, args: List<String>): String {
        val git = repo.getGit()
        val sub = args.firstOrNull() ?: "list"
        return when (sub) {
            "list" -> {
                val stashes = git.stashList().call()
                if (stashes.isEmpty()) return "(no stashes)"
                stashes.mapIndexed { i, c -> "stash@{$i}: ${c.shortMessage}" }.joinToString("\n")
            }
            "save", "push", "" -> {
                val ref = git.stashCreate().call()
                if (ref == null) "nothing to stash" else "Saved working directory and index state\n${ref.name.take(7)}"
            }
            "pop" -> {
                val stashes = git.stashList().call().toList()
                if (stashes.isEmpty()) return "No stash entries found"
                git.stashApply().setStashRef("stash@{0}").call()
                git.stashDrop().setStashRef(0).call()
                "Applied and dropped stash@{0}: ${stashes[0].shortMessage}"
            }
            "drop" -> {
                val index = args.getOrNull(1)?.removePrefix("stash@{")?.removeSuffix("}")?.toIntOrNull() ?: 0
                val stashes = git.stashList().call().toList()
                if (index >= stashes.size) return "No stash entry at index $index"
                git.stashDrop().setStashRef(index).call()
                "Dropped stash@{$index}: ${stashes[index].shortMessage}"
            }
            else -> "unknown stash subcommand: $sub\nUsage: stash [list|push|pop|drop [stash@{n}]]"
        }
    }

    private fun runFetch(repo: Repo, args: List<String>): String {
        val remote = args.firstOrNull { !it.startsWith("-") } ?: "origin"
        val git = repo.getGit()
        val cmd = git.fetch()
            .setRemote(remote)
            .setTransportConfigCallback(SgitTransportCallback())
        applyCredentials(repo, remote, cmd)
        val result = cmd.call()
        val updates = result.trackingRefUpdates
        if (updates.isEmpty()) return "Already up to date."
        val sb = StringBuilder("From $remote\n")
        updates.forEach { u ->
            val branch = u.localName.removePrefix("refs/remotes/$remote/")
            val oldSha = u.oldObjectId?.name?.take(7) ?: "0000000"
            val newSha = u.newObjectId.name.take(7)
            sb.appendLine("   $oldSha..$newSha  $branch -> $remote/$branch")
        }
        return sb.toString().trimEnd()
    }

    private fun runPull(repo: Repo, args: List<String>): String {
        val remote = args.firstOrNull { !it.startsWith("-") } ?: "origin"
        val git = repo.getGit()
        val cmd = git.pull()
            .setRemote(remote)
            .setTransportConfigCallback(SgitTransportCallback())
        applyCredentials(repo, remote, cmd)
        val result = cmd.call()
        if (!result.isSuccessful) {
            val status = result.mergeResult?.mergeStatus?.toString()
                ?: result.rebaseResult?.status?.toString()
                ?: "unknown"
            return "pull failed: $status"
        }
        val updates = result.fetchResult?.trackingRefUpdates
        if (updates.isNullOrEmpty()) return "Already up to date."
        val sb = StringBuilder()
        updates.forEach { u ->
            val branch = u.localName.removePrefix("refs/remotes/$remote/")
            val oldSha = u.oldObjectId?.name?.take(7) ?: "0000000"
            val newSha = u.newObjectId.name.take(7)
            sb.appendLine("   $oldSha..$newSha  $branch")
        }
        sb.append("Fast-forward")
        return sb.toString().trimEnd()
    }

    private fun runSubmodule(repo: Repo, args: List<String>): String {
        val sub = args.firstOrNull() ?: "status"
        val options = args.drop(1)
        val paths = options.filter { !it.startsWith("-") }.map { it.trimEnd('/') }
        val db = repo.getGit().repository
        if (sub in listOf("status", "init", "update") && Git.wrap(db).submoduleStatus().call().isEmpty()) {
            return "(no submodules)"
        }
        val out = StringBuilder()
        when (sub) {
            "status" -> submoduleStatus(db, "", "--recursive" in options, out)
            "init" -> submoduleInit(db, "", paths, out)
            "update" -> {
                val notInitialized = updateSubmodules(repo, db, "", paths,
                    "--init" in options, "--recursive" in options, out)
                notInitialized.forEach {
                    out.appendLine("Submodule '$it' is not initialized, use 'submodule update --init'")
                }
            }
            else -> return "unknown submodule subcommand: $sub\n" +
                "Usage: submodule [status [--recursive] | init [path] | update [--init] [--recursive] [path]]"
        }
        return out.toString().trimEnd().ifEmpty { "Submodules already up to date." }
    }

    /** Same markers as git: '-' not initialized, '+' checked out commit differs from the one
     * the superproject records, ' ' in sync. */
    private fun submoduleStatus(db: Repository, prefix: String, recursive: Boolean, out: StringBuilder) {
        val statuses = Git.wrap(db).submoduleStatus().call()
        for ((path, status) in statuses.toSortedMap()) {
            val marker = when (status.type) {
                SubmoduleStatusType.UNINITIALIZED, SubmoduleStatusType.MISSING -> "-"
                SubmoduleStatusType.REV_CHECKED_OUT -> "+"
                else -> " "
            }
            val id = (if (marker == "+") status.headId else status.indexId)?.name ?: "0".repeat(40)
            out.appendLine("$marker$id $prefix$path")
            if (recursive && marker != "-") {
                SubmoduleWalk.getSubmoduleRepository(db, path)?.use {
                    submoduleStatus(it, "$prefix$path/", true, out)
                }
            }
        }
    }

    private fun submoduleInit(db: Repository, prefix: String, paths: List<String>, out: StringBuilder) {
        val initialized = Git.wrap(db).submoduleInit().apply { paths.forEach { addPath(it) } }.call()
        initialized.sorted().forEach { out.appendLine("Submodule '$prefix$it' registered") }
    }

    /** Updates submodules one at a time, so each is fetched with the credentials for its own
     * URL's host (submodules are often on a different host than the superproject). Like git,
     * submodules that were never initialized are skipped; their paths are returned so the
     * caller can point to --init. */
    private fun updateSubmodules(repo: Repo, db: Repository, prefix: String, paths: List<String>,
                                 init: Boolean, recursive: Boolean, out: StringBuilder): List<String> {
        if (init) {
            submoduleInit(db, prefix, paths, out)
        }
        val notInitialized = mutableListOf<String>()
        SubmoduleWalk.forIndex(db).use { walk ->
            while (walk.next()) {
                val path = walk.path
                if (paths.isNotEmpty() && path !in paths) continue
                val url = walk.configUrl
                if (url == null) {
                    notInitialized.add(prefix + path)
                    continue
                }
                // JGit only fetches into an already cloned submodule when asked; like git, fetch
                // when it doesn't have the commit the superproject records (e.g. after a pull)
                val (before, missingCommit) = walk.repository?.use {
                    it.resolve(Constants.HEAD) to !it.objectDatabase.has(walk.objectId)
                } ?: (null to false)
                val cmd = Git.wrap(db).submoduleUpdate()
                    .addPath(path)
                    .setFetch(missingCommit)
                    .setTransportConfigCallback(SgitTransportCallback())
                RepoCredentials.resolve(repo, url)?.let {
                    cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(it.username, it.password))
                }
                try {
                    cmd.call()
                } catch (e: Exception) {
                    // Like git, one unreachable submodule doesn't stop the others from updating
                    out.appendLine("error: failed to update '$prefix$path': ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                // Like git, only report submodules whose checkout actually changed
                val after = SubmoduleWalk.getSubmoduleRepository(db, path)?.use { it.resolve(Constants.HEAD) }
                if (after != null && after != before) {
                    out.appendLine("Submodule path '$prefix$path': checked out '${walk.objectId.name}'")
                }
                if (recursive) {
                    walk.repository?.use {
                        notInitialized += updateSubmodules(repo, it, "$prefix$path/", emptyList(),
                            init, true, out)
                    }
                }
            }
        }
        return notInitialized
    }

    /** Same lookup as RepoOpTask.setCredentials(TransportCommand, String): credentials are
     * resolved per remote host (connected account, then this repo's credentials for that host),
     * so each of a repo's remotes gets its own host's credentials (#59, #87). */
    private fun applyCredentials(repo: Repo, remoteName: String, cmd: TransportCommand<*, *>) {
        RepoCredentials.resolve(repo, repo.getRemoteURL(remoteName))?.let {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(it.username, it.password))
        }
    }

    private fun helpText() = """
supported commands:
  status              show working tree status
  log                 show commit history
    --oneline         compact one-line format
    --graph           with branch graph prefix
    -n <N> or -<N>    limit number of commits (default 20)
    --since=<date>    e.g. --since=yesterday, --since=2025-01-01
  diff                show unstaged changes
    --staged          show staged changes
  branch              list local branches
    -a                list all branches (local + remote)
    -r                list remote branches only
  stash               stash / list / pop / drop
    list              list stash entries
    push              save working tree to stash
    pop               apply and drop stash@{0}
    drop [stash@{n}]  drop a specific stash entry
  fetch [remote]      fetch from remote (default: origin)
  pull [remote]       fetch + merge into current branch (default: origin)
  submodule           show submodule status
    status            list submodules and their commits
    init [path]       register submodules from .gitmodules
    update [path]     check out the commits the repo records
      --init          initialize submodules first
      --recursive     include nested submodules
  help                show this help
    """.trimIndent()

    private fun parseSinceDate(since: String): Long? {
        if (since == "yesterday") {
            return System.currentTimeMillis() - 86_400_000L
        }
        val fmt1 = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fmt2 = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return runCatching { fmt2.parse(since)?.time }
            .getOrNull()
            ?: runCatching { fmt1.parse(since)?.time }.getOrNull()
    }

    private fun tokenize(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        for (ch in cmd) {
            when {
                inQuote && ch == quoteChar -> inQuote = false
                !inQuote && (ch == '"' || ch == '\'') -> { inQuote = true; quoteChar = ch }
                !inQuote && ch == ' ' -> {
                    if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
