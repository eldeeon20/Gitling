package com.manichord.mgit.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import timber.log.Timber

/**
 * Makes files Android won't let Gitling read readable again, without any storage permission.
 *
 * With "Make repos visible to other apps" on, repos live in Android/media/<pkg>, and on API 33+
 * MediaProvider denies Gitling raw File access to image, video and audio files another app wrote
 * there (EACCES, see FsUtils.findEaccesFile) unless it holds READ_MEDIA_*, which Google Play's
 * photo and video permissions policy doesn't allow for this use. A file Gitling writes itself is
 * owned by Gitling and always readable, so the fix is to replace each blocked file with an
 * identical copy Gitling writes: the bytes are read through a folder grant the user gives once
 * in the system folder picker (ExternalStorageProvider reads on Gitling's behalf), and the
 * original is deleted through the same grant. The grant is persisted, so later repairs under the
 * same folder need no picker.
 */
object BlockedFileRepair {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary:"
    private const val TEMP_SUFFIX = ".gitling-tmp"

    class Result(@JvmField val fixed: List<File>, @JvmField val failed: List<File>)

    /** True if opening [file] for reading fails with EACCES (as opposed to not existing). */
    @JvmStatic
    fun isBlocked(file: File): Boolean = try {
        FileInputStream(file).close()
        false
    } catch (e: FileNotFoundException) {
        e.message?.contains("EACCES") == true
    }

    /** Every blocked regular file under [dir], including inside .git. Symlinks aren't followed,
     * so a link out of the repo can't pull unrelated files into a repair. */
    @JvmStatic
    fun findBlockedFiles(dir: File): List<File> {
        val blocked = ArrayList<File>()
        val pending = ArrayDeque<File>()
        pending.add(dir)
        while (pending.isNotEmpty()) {
            val children = pending.removeLast().listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) {
                    continue
                }
                if (child.isDirectory) {
                    pending.add(child)
                } else if (child.isFile && isBlocked(child)) {
                    blocked.add(child)
                }
            }
        }
        return blocked
    }

    private fun isSymlink(file: File): Boolean = try {
        (Os.lstat(file.path).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    } catch (e: ErrnoException) {
        false
    }

    /** ExternalStorageProvider's document id for [file], or null if it isn't on primary
     * shared storage (the only volume Gitling's repo roots live on). */
    @JvmStatic
    fun documentIdFor(file: File): String? {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = file.absolutePath
        return when {
            path == root -> PRIMARY_VOLUME
            path.startsWith("$root/") -> PRIMARY_VOLUME + path.substring(root.length + 1)
            else -> null
        }
    }

    /** Where the folder picker should open, so the user only has to confirm. */
    @JvmStatic
    fun initialPickerUri(dir: File): Uri? =
        documentIdFor(dir)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it) }

    /** Whether the folder [treeUri] grants access to includes [file]. */
    @JvmStatic
    fun covers(treeUri: Uri, file: File): Boolean {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return false
        }
        val fileId = documentIdFor(file) ?: return false
        val treeId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val prefix = if (treeId.endsWith(":") || treeId.endsWith("/")) treeId else "$treeId/"
        return fileId == treeId || fileId.startsWith(prefix)
    }

    /** A folder grant the user already gave (see [persistGrant]) that includes [file]. */
    @JvmStatic
    fun findGrant(context: Context, file: File): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission && covers(it.uri, file) }
            ?.uri

    /** Keeps the picker's grant across restarts, so later repairs need no picker. */
    @JvmStatic
    fun persistGrant(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Still usable for this repair; the user will just see the picker again next time
            Timber.w(e, "Could not persist access to %s", treeUri)
        }
    }

    /** Replaces every blocked file under [dir] with Gitling's own copy, through [treeUri].
     * Does disk I/O, so call it off the main thread. */
    @JvmStatic
    fun repair(context: Context, treeUri: Uri, dir: File): Result {
        val fixed = ArrayList<File>()
        val failed = ArrayList<File>()
        for (file in findBlockedFiles(dir)) {
            try {
                replaceWithOwnCopy(context, treeUri, file)
                fixed.add(file)
            } catch (e: Exception) {
                Timber.e(e, "Could not replace blocked file %s", file)
                failed.add(file)
            }
        }
        return Result(fixed, failed)
    }

    /** The copy is written in full next to the original before the original is deleted, and
     * then renamed into place, so a failure at any step before the delete leaves the original
     * untouched. Size and modification time are kept, so git sees the file as unchanged. */
    private fun replaceWithOwnCopy(context: Context, treeUri: Uri, file: File) {
        val docId = documentIdFor(file) ?: throw IOException("Not on shared storage: $file")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val resolver = context.contentResolver
        val lastModified = file.lastModified()
        val expectedLength = file.length()
        val temp = File(file.parentFile, "." + file.name + TEMP_SUFFIX)
        temp.delete()
        try {
            val input = resolver.openInputStream(docUri) ?: throw IOException("Can't open $docUri")
            input.use { src -> FileOutputStream(temp).use { dst -> src.copyTo(dst) } }
            if (temp.length() != expectedLength) {
                throw IOException("Copied ${temp.length()} of $expectedLength bytes of $file")
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        if (!DocumentsContract.deleteDocument(resolver, docUri)) {
            temp.delete()
            throw IOException("Could not delete $file")
        }
        if (!temp.renameTo(file)) {
            // Original is already gone, so the copy must not be lost: fall back to a plain copy
            // and keep the temp file if even that fails, so the user can recover it by hand
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        if (lastModified > 0) {
            file.setLastModified(lastModified)
        }
    }
}
