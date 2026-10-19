package com.manichord.mgit.importrepo

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS

/**
 * Copies an existing Git repository into Gitling's repo storage (#57), either from a folder the
 * user picks in the system folder picker or from a ZIP file. It's always a copy: JGit needs real
 * files, and Gitling has no access to arbitrary folders (see #49), only to what the picker grants.
 *
 * Everything is first written to a hidden temp folder next to the destination and only renamed
 * into place once complete, so a failed or cancelled import never leaves a half-copied repo
 * behind (see [cleanUpStaleImports] for imports interrupted by the process dying).
 */
object RepoImporter {

    private const val TEMP_SUFFIX = ".gitling-import"
    private const val MAX_DEPTH = 64
    private const val BUFFER_SIZE = 64 * 1024

    enum class Problem {
        /** No .git folder in the picked folder. */
        NOT_A_REPO,
        /** The user picked a repository's .git folder itself. */
        PICKED_GIT_DIR,
        /** A bare repository: Git data but no working tree. */
        BARE_REPO,
        /** .git is a file pointing elsewhere (a worktree or a submodule checkout). */
        GIT_FILE,
        ZIP_NO_REPO,
        ZIP_MULTIPLE_REPOS,
        /** Not a readable ZIP, or an entry path that would land outside the destination. */
        ZIP_INVALID,
        NO_SPACE,
        READ_FAILED,
        CANCELLED
    }

    class ImportException(val problem: Problem, val detail: String? = null, cause: Throwable? = null) :
        IOException(detail ?: problem.name, cause)

    class Result(
        val dir: File,
        val fileCount: Int,
        val bytes: Long,
        /** core.filemode / core.symlinks were on and had to be turned off, see [adjustConfig]. */
        val turnedOffFileModeOrSymlinks: Boolean,
        /** ZIP entries outside the repository's folder, which weren't imported. */
        val skippedOutsideRepo: Int
    )

    /** [done] and [total] are in bytes; [total] is -1 when the size isn't known. */
    fun interface ProgressListener {
        fun onProgress(done: Long, total: Long)
    }

    /** The folder or ZIP file name, as a starting point for the repo name. */
    fun suggestedName(context: Context, uri: Uri, isZip: Boolean): String {
        val name = if (isZip) {
            queryString(context, uri, OpenableColumns.DISPLAY_NAME)
        } else {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            queryString(context, docUri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } ?: return ""
        return name.removeSuffix(".zip").removeSuffix(".ZIP").removeSuffix(".git").replace("/", "_").trim()
    }

    /** Removes temp folders left by an import that was interrupted by the app being killed. */
    fun cleanUpStaleImports(repoRoot: File) {
        repoRoot.listFiles { f -> f.isDirectory && f.name.startsWith(".") && f.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    /** Throws an [ImportException] saying what's wrong if the picked folder isn't a repository
     * Gitling can import. Only lists the folder itself, so it's quick enough to run before
     * asking for a name. */
    fun checkFolder(context: Context, treeUri: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        checkFolderIsRepo(context, treeUri, rootId, listChildren(context, treeUri, rootId))
    }

    fun importFolder(context: Context, treeUri: Uri, destDir: File, progress: ProgressListener,
                     isCancelled: () -> Boolean): Result {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootChildren = listChildren(context, treeUri, rootId)
        checkFolderIsRepo(context, treeUri, rootId, rootChildren)

        // List everything first, so progress can be shown against the total size
        val files = ArrayList<Pair<String, Doc>>()
        val dirs = ArrayList<String>()
        val pending = ArrayDeque<Triple<String, String, Int>>() // relative path, doc id, depth
        pending.add(Triple("", rootId, 0))
        while (pending.isNotEmpty()) {
            if (isCancelled()) throw ImportException(Problem.CANCELLED)
            val (path, docId, depth) = pending.removeFirst()
            if (depth > MAX_DEPTH) {
                throw ImportException(Problem.READ_FAILED, "Folders are nested too deeply at $path")
            }
            val children = if (docId == rootId) rootChildren else listChildren(context, treeUri, docId)
            for (child in children) {
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.isDir) {
                    dirs.add(childPath)
                    pending.add(Triple(childPath, child.id, depth + 1))
                } else {
                    files.add(childPath to child)
                }
            }
        }
        val total = files.sumOf { it.second.size.coerceAtLeast(0) }

        val temp = tempDirFor(destDir)
        try {
            for (dir in dirs) {
                mkdirs(File(temp, dir))
            }
            var done = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            for ((path, doc) in files) {
                val target = File(temp, path)
                mkdirs(target.parentFile!!)
                val input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.id))
                    ?: throw ImportException(Problem.READ_FAILED, "Can't open $path")
                input.use { src ->
                    FileOutputStream(target).use { dst ->
                        while (true) {
                            if (isCancelled()) throw ImportException(Problem.CANCELLED)
                            val n = src.read(buffer)
                            if (n < 0) break
                            dst.write(buffer, 0, n)
                            done += n
                            progress.onProgress(done, total)
                        }
                    }
                }
            }
            val turnedOff = adjustConfig(temp)
            moveIntoPlace(temp, destDir)
            return Result(destDir, files.size, done, turnedOff, 0)
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw asImportException(e)
        }
    }

    fun importZip(context: Context, zipUri: Uri, destDir: File, progress: ProgressListener,
                  isCancelled: () -> Boolean): Result {
        val total = queryString(context, zipUri, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L
        val temp = tempDirFor(destDir)
        val tempCanonical = temp.canonicalPath + File.separator
        var fileCount = 0
        var bytes = 0L
        try {
            val raw = context.contentResolver.openInputStream(zipUri)
                ?: throw ImportException(Problem.READ_FAILED, "Can't open the ZIP file")
            val counting = CountingInputStream(raw)
            ZipInputStream(counting).use { zip ->
                val buffer = ByteArray(BUFFER_SIZE)
                var sawEntry = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    sawEntry = true
                    if (isCancelled()) throw ImportException(Problem.CANCELLED)
                    val name = entry.name.replace('\\', '/')
                    // Resource forks macOS adds when zipping, never part of the repo
                    if (name.startsWith("__MACOSX/")) continue
                    val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
                    if (name.startsWith("/") || segments.any { it == ".." }) {
                        throw ImportException(Problem.ZIP_INVALID, "Unsafe path in the ZIP: $name")
                    }
                    if (segments.isEmpty()) continue
                    val target = File(temp, segments.joinToString(File.separator))
                    // Belt and braces for the check above (zip slip)
                    if (!target.canonicalPath.startsWith(tempCanonical)) {
                        throw ImportException(Problem.ZIP_INVALID, "Unsafe path in the ZIP: $name")
                    }
                    if (entry.isDirectory) {
                        mkdirs(target)
                        continue
                    }
                    mkdirs(target.parentFile!!)
                    // A symbolic link entry is written as a plain file containing its target,
                    // which is how git itself stores links when core.symlinks is false
                    FileOutputStream(target).use { dst ->
                        while (true) {
                            if (isCancelled()) throw ImportException(Problem.CANCELLED)
                            val n = zip.read(buffer)
                            if (n < 0) break
                            dst.write(buffer, 0, n)
                            bytes += n
                            progress.onProgress(counting.count, total)
                        }
                    }
                    fileCount++
                }
                if (!sawEntry) throw ImportException(Problem.ZIP_INVALID, "The file is empty or not a ZIP file")
            }

            // The repo may sit in a folder inside the ZIP (the usual way a folder is zipped);
            // only that folder is imported
            val root = findZipRepoRoot(temp)
            val outside = if (root == temp) 0 else countFilesOutside(temp, root)
            val turnedOff = adjustConfig(root)
            moveIntoPlace(root, destDir)
            temp.deleteRecursively()
            return Result(destDir, fileCount - outside, bytes, turnedOff, outside)
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw if (e is ZipException) ImportException(Problem.ZIP_INVALID, e.message, e) else asImportException(e)
        }
    }

    private fun checkFolderIsRepo(context: Context, treeUri: Uri, rootId: String, children: List<Doc>) {
        val dotGit = children.firstOrNull { it.name == ".git" }
        when {
            dotGit != null && dotGit.isDir -> return
            dotGit != null -> throw ImportException(Problem.GIT_FILE)
            looksLikeGitDir(children.map { it.name }.toSet()) -> {
                val rootName = queryString(context,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                throw ImportException(if (rootName == ".git") Problem.PICKED_GIT_DIR else Problem.BARE_REPO)
            }
            else -> throw ImportException(Problem.NOT_A_REPO)
        }
    }

    /** Git's own data, as in a .git folder or a bare repository. refs/ can be missing (an empty
     * folder dropped by a copy, with every ref in packed-refs), so any of these will do. */
    private fun looksLikeGitDir(names: Set<String>) = "HEAD" in names && "objects" in names &&
        ("refs" in names || "packed-refs" in names || "config" in names)

    /** The shallowest folder with a .git folder in it. Doesn't look inside .git folders. */
    private fun findZipRepoRoot(temp: File): File {
        var level = listOf(temp)
        var sawBare = false
        var sawGitFile = false
        repeat(MAX_DEPTH) {
            val found = level.filter { File(it, ".git").isDirectory }
            if (found.size == 1) return found[0]
            if (found.size > 1) throw ImportException(Problem.ZIP_MULTIPLE_REPOS)
            level.forEach { dir ->
                if (File(dir, ".git").isFile) sawGitFile = true
                if (looksLikeGitDir(dir.list()?.toSet() ?: emptySet())) sawBare = true
            }
            level = level.flatMap { dir ->
                dir.listFiles { f -> f.isDirectory && f.name != ".git" }?.toList() ?: emptyList()
            }
            if (level.isEmpty()) return@repeat
        }
        throw ImportException(when {
            sawBare -> Problem.BARE_REPO
            sawGitFile -> Problem.GIT_FILE
            else -> Problem.ZIP_NO_REPO
        })
    }

    private fun countFilesOutside(temp: File, root: File): Int {
        val rootPath = root.canonicalPath + File.separator
        return temp.walkTopDown().count { it.isFile && !it.canonicalPath.startsWith(rootPath) }
    }

    /**
     * Gitling's storage can't keep file permissions or symbolic links, so a copied executable
     * becomes a regular file and a link becomes a file containing its target. With core.filemode
     * and core.symlinks on, as on a computer, git would report every such file as modified;
     * turned off, git ignores the difference and keeps the modes recorded in the index, which is
     * what repos cloned by Gitling already have. Returns whether either had to be turned off.
     */
    private fun adjustConfig(repoDir: File): Boolean {
        val config = FileBasedConfig(File(repoDir, ".git/config"), FS.DETECTED)
        config.load()
        val fileMode = config.getBoolean("core", null, "filemode", true)
        val symlinks = config.getBoolean("core", null, "symlinks", true)
        if (!fileMode && !symlinks) return false
        config.setBoolean("core", null, "filemode", false)
        config.setBoolean("core", null, "symlinks", false)
        config.save()
        return true
    }

    private fun tempDirFor(destDir: File): File {
        val temp = File(destDir.parentFile, ".${destDir.name}$TEMP_SUFFIX")
        temp.deleteRecursively()
        mkdirs(temp)
        return temp
    }

    private fun moveIntoPlace(from: File, destDir: File) {
        if (destDir.exists()) throw ImportException(Problem.READ_FAILED, "${destDir.name} already exists")
        if (!from.renameTo(destDir)) throw ImportException(Problem.READ_FAILED, "Can't move the copy into place")
    }

    private fun mkdirs(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.name}")
    }

    private fun asImportException(e: Exception): ImportException = when {
        e is ImportException -> e
        e.message?.contains("ENOSPC") == true -> ImportException(Problem.NO_SPACE, cause = e)
        else -> ImportException(Problem.READ_FAILED, e.message ?: e.javaClass.simpleName, e)
    }

    private class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private fun listChildren(context: Context, treeUri: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor: Cursor = context.contentResolver.query(uri, projection, null, null, null)
            ?: throw ImportException(Problem.READ_FAILED, "Can't list the folder")
        return cursor.use { c ->
            val result = ArrayList<Doc>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                // A name with a separator can't be a real file name; never let it shape a path
                if (name.contains('/') || name == "." || name == "..") continue
                result.add(Doc(
                    id = c.getString(0),
                    name = name,
                    isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) -1 else c.getLong(3)
                ))
            }
            result
        }
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
