package me.sheimi.android.utils;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import android.webkit.MimeTypeMap;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.sheimi.android.activities.SheimiFragmentActivity;
import me.sheimi.sgit.MGitApplication;
import me.sheimi.sgit.R;
import timber.log.Timber;

/**
 * Created by sheimi on 8/8/13.
 */
public class FsUtils {

    public static final SimpleDateFormat TIMESTAMP_FORMATTER = new SimpleDateFormat(
            "yyyyMMdd_HHmmss", Locale.getDefault());

    public static final String PROVIDER_AUTHORITY = "com.maneeshacooray.gitling.fileprovider";
    public static final String TEMP_DIR = "temp";

    private FsUtils() {
    }

    public static File createTempFile(String subfix) throws IOException {
        File dir = getExternalDir(TEMP_DIR);
        String fileName = TIMESTAMP_FORMATTER.format(new Date());
        File file = File.createTempFile(fileName, subfix, dir);
        file.deleteOnExit();
        return file;
    }

    /**
     * Get a File representing a dir within the external shared location where files can be stored specific to this app
     * creating the dir if it doesn't already exist
     *
     * @param dirname
     * @return
     */
    public static File getExternalDir(String dirname) {
        return getExternalDir(dirname, true);
    }

    /**
     *
     * @param dirname
     * @return
     */
    public static File getInternalDir(String dirname) { return getExternalDir(dirname, true, false); }

    /**
     * Get a File representing a dir within the external shared location where files can be stored specific to this app
     *
     * @param dirname
     * @param isCreate  create the dir if it does not already exist
     * @return
     */
    public static File getExternalDir(String dirname, boolean isCreate) { return getExternalDir(dirname, isCreate, true); }

    /**
     *
     * Get a File representing a dir within the location where files can be stored specific to this app
     *
     * @param dirname  name of the dir to return
     * @param isCreate  create the dir if it does not already exist
     * @param isExternal if true, will use external *shared* storage
     * @return
     */
    public static File getExternalDir(String dirname, boolean isCreate, boolean isExternal) {
        File mDir = new File(getAppDir(isExternal), dirname);
        if (!mDir.exists() && isCreate) {
            mDir.mkdir();
        }
        return mDir;
    }

    /**
     * Get a File representing the location where files can be stored specific to this app
     *
     * @param isExternal if true, will use external *shared* storage
     * @return
     */
    public static File getAppDir(boolean isExternal) {
        SheimiFragmentActivity activeActivity = BasicFunctions.getActiveActivity();
        // Fall back to Application context when called from a non-Activity context (e.g. widget)
        Context context = activeActivity != null ? activeActivity : MGitApplication.getContext();
        if (isExternal) {
            return context.getExternalFilesDir(null);
        } else {
            return context.getFilesDir();
        }
    }

    /** Get a File representing a dir within Context.getExternalMediaDirs() -- like
     * getExternalDir(), this needs no storage permission on any Android version, but unlike
     * Android/data (where getExternalDir() lives), Android/media is NOT subject to Android 11's
     * block on other apps browsing into another app's external storage. So files here are
     * reachable by other apps that have their own storage access (e.g. a file manager with "all
     * files" granted), without Gitling itself needing to request anything. */
    public static File getMediaDir(String dirname, boolean isCreate) {
        SheimiFragmentActivity activeActivity = BasicFunctions.getActiveActivity();
        // Fall back to Application context when called from a non-Activity context (e.g. widget)
        Context context = activeActivity != null ? activeActivity : MGitApplication.getContext();
        File[] mediaDirs = context.getExternalMediaDirs();
        File mDir = new File(mediaDirs[0], dirname);
        if (!mDir.exists() && isCreate) {
            mDir.mkdirs();
        }
        return mDir;
    }

    /** Public Documents folder (e.g. /storage/emulated/0/Documents): reachable by every app
     * without any storage permission on the reader side, but writing here on Android 11+ needs
     * MANAGE_EXTERNAL_STORAGE (all-files access), which the user grants from Settings. */
    public static File getDocumentsDir(String dirname, boolean isCreate) {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File mDir = new File(base, dirname);
        if (!mDir.exists() && isCreate) {
            mDir.mkdirs();
        }
        return mDir;
    }

    /** Whether Gitling can write to shared storage (public Documents, Downloads, etc).
     * API 30+ uses the special "All files access" grant; older releases use the legacy
     * WRITE_EXTERNAL_STORAGE runtime permission (see AndroidManifest's maxSdkVersion=29). */
    public static boolean hasSharedStorageWriteAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The system screen where the user grants "All files access" on API 30+. */
    public static Intent allFilesAccessSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /** True when {@code file} needs broad storage access to be writable: under the primary
     * shared volume but not inside Android/data or Android/media, which are already accessible
     * to Gitling without any permission. */
    public static boolean isOnSharedStorage(File file) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String path = file.getAbsolutePath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            return false;
        }
        return !path.startsWith(root + "/Android/data/")
                && !path.startsWith(root + "/Android/media/");
    }

    /** Verifies that {@code dir} exists and can actually be written to before JGit starts
     * cloning into it. mkdirs() alone is not enough: it returns false for an existing
     * directory, and a directory can exist but still reject every write (missing All files
     * access, revoked permission, read-only volume). Throws with the exact path so the clone
     * error dialog tells the user which location is the problem. */
    public static void ensureWritableDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory " + dir.getAbsolutePath());
        }
        if (!dir.canWrite()) {
            throw new IOException("Directory is not writable: " + dir.getAbsolutePath());
        }
        File probe = new File(dir, ".gitling-write-test-" + System.nanoTime());
        try {
            if (!probe.createNewFile() && !probe.exists()) {
                throw new IOException("Could not create files in " + dir.getAbsolutePath());
            }
        } finally {
            if (probe.exists() && !probe.delete()) {
                Timber.w("Could not delete write probe %s", probe);
            }
        }
    }

    /** Matches the message java.io.FileNotFoundException carries on Android when the underlying
     * open(2) syscall fails, e.g. "/path/to/file.jpg: open failed: EACCES (Permission denied)". */
    private static final Pattern EACCES_PATH_PATTERN =
            Pattern.compile("^(.*): open failed: EACCES");

    /** Walks the cause chain of {@code t} looking for the file an EACCES failure was reported
     * against, or null if none of the causes match that pattern. On recent Android, MediaProvider
     * denies raw File access to image/video/audio files -- even ones sitting inside this app's
     * own Android/media/&lt;pkg&gt; directory (used when "Make repos visible to other apps" is
     * on) and written there by another app -- unless the app holds READ_MEDIA_IMAGES (or
     * READ_EXTERNAL_STORAGE pre-API 33). JGit's
     * FileTreeIterator/AddCommand open working-tree files directly via java.io.File, so diffing or
     * staging such a file throws this before Gitling gets a chance to ask for that permission. */
    public static File findEaccesFile(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                Matcher matcher = EACCES_PATH_PATTERN.matcher(msg);
                if (matcher.find()) {
                    return new File(matcher.group(1));
                }
            }
            t = t.getCause();
        }
        return null;
    }

    /** Wraps an EACCES failure (see {@link #findEaccesFile}) with a message explaining the likely
     * cause and next step, in place of JGit's raw "open failed: EACCES" message. API 33+ has no
     * permission to point at (see SheimiFragmentActivity.requestMediaFileAccess). */
    public static Throwable wrapEaccesFile(Throwable cause, File eaccesFile) {
        int message = Build.VERSION.SDK_INT >= 33
                ? R.string.error_media_file_blocked
                : R.string.error_media_permission_denied;
        String friendly = MGitApplication.getContext().getString(message, eaccesFile.getName());
        return new IOException(friendly, cause);
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url
                .toLowerCase(Locale.getDefault()));
        if (extension != null && !overrideMimeToPlainText(extension)) {
            MimeTypeMap mime = MimeTypeMap.getSingleton();
            type = mime.getMimeTypeFromExtension(extension);
        }
        if (type == null) {
            type = "text/plain";
        }
        return type;
    }

    public static String getMimeType(File file) {
        return getMimeType(Uri.fromFile(file).toString());
    }

    public static void openFile(SheimiFragmentActivity activity, File file) {
        Uri uri = FileProvider.getUriForFile(activity, PROVIDER_AUTHORITY, file);
        String mimeType = FsUtils.getMimeType(uri.toString());
        Intent intent = new Intent(Intent.ACTION_EDIT);
        intent.setDataAndType(uri, mimeType);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            activity.startActivity(intent);
            activity.forwardTransition();
        } catch (ActivityNotFoundException e) {
            // Looks like many editor apps only register for VIEW not EDIT intents :-(
            intent.setAction(Intent.ACTION_VIEW);
            try {
                activity.startActivity(intent);
                activity.forwardTransition();
            }
            catch (ActivityNotFoundException e1) {
                activity.showMessageDialog(R.string.dialog_error_title, activity.getString(R.string.error_can_not_open_file));
            }
        }
    }

    public static void deleteFile(File file) {
        File to = new File(file.getAbsolutePath() + System.currentTimeMillis());
        file.renameTo(to);
        deleteFileInner(to);
    }

    /**
     * For some file types, need to override their mimetype to be text/plain
     * @param fileExtension
     * @return
     */
    private static boolean overrideMimeToPlainText(String fileExtension) {
        // for now only Typescript needs this override as Mime DB uses ".ts" for mpg2 files
        return "ts".equalsIgnoreCase(fileExtension);
    }

    private static void deleteFileInner(File file) {
        if (!file.isDirectory()) {
            file.delete();
            return;
        }
        try {
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            //TODO 
            e.printStackTrace();
        }
    }

    public static void copyFile(File from, File to) {
        try {
            FileUtils.copyFile(from, to);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copyDirectory(File from, File to) {
        if (!from.exists())
            return;
        try {
            FileUtils.copyDirectory(from, to);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean renameDirectory(File dir, String name) {
        String newDirPath = dir.getParent() + File.separator + name;
        File newDirFile = new File(newDirPath);

        return dir.renameTo(newDirFile);
    }

    public static String getRelativePath(File file, File base) {
        return base.toURI().relativize(file.toURI()).getPath();
    }

    public static File joinPath(File dir, String relative_path) {
        return new File(dir.getAbsolutePath() + File.separator + relative_path);
    }

    public static boolean isTextMimeType(String mime) {
        return mime.startsWith("text") || mime.equals("application/javascript") || mime.equals("application/json");
    }
}
