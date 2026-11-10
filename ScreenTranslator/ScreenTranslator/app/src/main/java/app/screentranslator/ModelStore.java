package app.screentranslator;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Where the on-device AI model lives on the phone, and how it gets there: downloaded with
 * Android's own download manager (resumes after interruptions, shows a notification), or copied
 * from a .litertlm file the user already has.
 */
final class ModelStore {

    /** One downloadable model. */
    static final class Spec {
        final String id;
        final String name;
        final String fileName;
        final String url;
        final long sizeBytes;
        final int minRamGb;

        Spec(String id, String name, String fileName, String url, long sizeBytes, int minRamGb) {
            this.id = id;
            this.name = name;
            this.fileName = fileName;
            this.url = url;
            this.sizeBytes = sizeBytes;
            this.minRamGb = minRamGb;
        }

        String sizeText() {
            return gb(sizeBytes);
        }

        /** The model's web page (where it can also be downloaded by hand). */
        String page() {
            int end = url.indexOf("/resolve/");
            return end > 0 ? url.substring(0, end) : url;
        }
    }

    // Google's Gemma 4 models packaged for phones (Apache 2.0 licence), pinned to exact versions.
    static final Spec E2B = new Spec("e2b", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/"
                    + "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
            2_588_147_712L, 8);
    static final Spec E4B = new Spec("e4b", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/"
                    + "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
            3_659_530_240L, 12);

    /** Anything smaller than this can't be a complete model. */
    private static final long MIN_VALID_BYTES = 500_000_000L;
    private static final String FOLDER = "models";

    /** A download that's in progress, waiting, or failed. */
    static final class Progress {
        final int status;     // DownloadManager.STATUS_*
        final long done;
        final long total;
        final int reason;     // DownloadManager.COLUMN_REASON

        Progress(int status, long done, long total, int reason) {
            this.status = status;
            this.done = done;
            this.total = total;
            this.reason = reason;
        }

        boolean failed() {
            return status == DownloadManager.STATUS_FAILED;
        }
    }

    interface CopyProgress {
        void onProgress(long copiedBytes);
    }

    private ModelStore() {
    }

    static Spec byId(String id) {
        return E4B.id.equals(id) ? E4B : E2B;
    }

    static File file(Context context, Spec spec) {
        return new File(directory(context), spec.fileName);
    }

    private static File directory(Context context) {
        File base = context.getExternalFilesDir(null);
        return new File(base != null ? base : context.getFilesDir(), FOLDER);
    }

    /** True when the model file is complete and can be used. */
    static boolean isReady(Context context, Spec spec) {
        File model = file(context, spec);
        return model.isFile() && model.length() >= MIN_VALID_BYTES && progress(context, spec) == null;
    }

    /** The current download, or null if none is running (a finished download counts as none). */
    static Progress progress(Context context, Spec spec) {
        Prefs prefs = new Prefs(context);
        long id = prefs.downloadId(spec.id);
        if (id < 0) {
            return null;
        }
        DownloadManager downloads = context.getSystemService(DownloadManager.class);
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                prefs.setDownloadId(spec.id, -1);   // removed from the system's download list
                return null;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                prefs.setDownloadId(spec.id, -1);
                return null;
            }
            long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            return new Progress(status, done, total > 0 ? total : spec.sizeBytes, reason);
        }
    }

    /** Starts the download. Throws IllegalStateException with a user-facing message if it can't. */
    static void startDownload(Context context, Spec spec, boolean allowMobileData) {
        if (context.getExternalFilesDir(null) == null) {
            throw new IllegalStateException("The phone's storage isn't available right now. Try again in a moment.");
        }
        cancelDownload(context, spec);
        File folder = directory(context);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        File target = file(context, spec);
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Couldn't replace the old model file.");
        }
        long free = new StatFs(folder.getPath()).getAvailableBytes();
        long needed = spec.sizeBytes + 300_000_000L;
        if (free < needed) {
            throw new IllegalStateException("Not enough free space. The model needs about " + gb(needed)
                    + "; this phone has " + gb(free) + " free.");
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(spec.url))
                .setTitle("Screen Translator AI model")
                .setDescription(spec.name + " (" + spec.sizeText() + ")")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, FOLDER, spec.fileName)
                .setAllowedOverMetered(allowMobileData)
                .setAllowedOverRoaming(false);
        long id = context.getSystemService(DownloadManager.class).enqueue(request);
        new Prefs(context).setDownloadId(spec.id, id);
    }

    static void cancelDownload(Context context, Spec spec) {
        Prefs prefs = new Prefs(context);
        long id = prefs.downloadId(spec.id);
        if (id >= 0) {
            context.getSystemService(DownloadManager.class).remove(id);
            prefs.setDownloadId(spec.id, -1);
        }
    }

    static boolean delete(Context context, Spec spec) {
        cancelDownload(context, spec);
        File model = file(context, spec);
        return !model.exists() || model.delete();
    }

    /** Copies a .litertlm file the user picked. Runs on a background thread. */
    static void importFrom(Context context, Uri source, Spec spec, CopyProgress progress) throws IOException {
        String name = displayName(context, source);
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".litertlm")) {
            throw new IOException("That's not a model file. Choose a file whose name ends in .litertlm.");
        }
        cancelDownload(context, spec);
        File folder = directory(context);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        File temp = new File(folder, spec.fileName + ".copying");
        long copied = 0;
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                throw new IOException("Couldn't open that file.");
            }
            byte[] buffer = new byte[1 << 20];
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                if (copied - lastReport >= 50_000_000L) {
                    lastReport = copied;
                    progress.onProgress(copied);
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
        if (copied < MIN_VALID_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("That file is too small to be an AI model.");
        }
        File target = file(context, spec);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Couldn't save the model file.");
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Name unknown; accept the file.
        }
        return null;
    }

    /** This phone's memory in whole GB (rounded up, e.g. 7.4 → 8). */
    static int phoneRamGb(Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        return (int) Math.ceil(info.totalMem / 1_000_000_000.0);
    }

    /** Explains a download that is waiting or failed, in plain words. */
    static String describe(Progress progress, Spec spec) {
        switch (progress.status) {
            case DownloadManager.STATUS_PENDING:
                return "Starting download…";
            case DownloadManager.STATUS_PAUSED:
                switch (progress.reason) {
                    case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                        return "Waiting for Wi-Fi (" + percent(progress) + " done). Connect to Wi-Fi, or allow "
                                + "mobile data below and download again.";
                    case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                        return "Waiting for an internet connection (" + percent(progress) + " done)…";
                    default:
                        return "Download paused, will continue shortly (" + percent(progress) + " done)…";
                }
            case DownloadManager.STATUS_FAILED:
                switch (progress.reason) {
                    case 401:
                    case 403:
                        return "Download failed: the model site asked for a sign-in. Download the file in a "
                                + "browser from " + spec.page() + " and use \"Use a model file from this "
                                + "phone\" below.";
                    case 404:
                        return "Download failed: the model file was not found (404).";
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        return "Download failed: not enough free space on the phone.";
                    default:
                        return "Download failed (code " + progress.reason + "). Tap Download to try again.";
                }
            default:
                return "Downloading… " + percent(progress) + " (" + gb(progress.done) + " of "
                        + gb(progress.total) + ")";
        }
    }

    private static String percent(Progress progress) {
        if (progress.total <= 0) {
            return "0%";
        }
        return Math.round(progress.done * 100.0 / progress.total) + "%";
    }

    static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
    }
}
