package de.danoeh.antennapod.core.service.download;

import android.content.Context;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.storage.AutomaticDownloadAlgorithm;

public class EpisodeDownloadService {

    private EpisodeDownloadService() {
    }

    /**
     * Executor service used by the autodownloadUndownloadedEpisodes method.
     */
    private static final ExecutorService autodownloadExec;

    private static AutomaticDownloadAlgorithm downloadAlgorithm = new AutomaticDownloadAlgorithm();

    static {
        autodownloadExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Removed downloaded episodes outside of the queue if the episode cache is full. Episodes with a smaller
     * 'playbackCompletionDate'-value will be deleted first.
     * <p/>
     * This method should NOT be executed on the GUI thread.
     *
     * @param context Used for accessing the DB.
     */
    public static void performAutoCleanup(final Context context) {
        UserPreferences.getEpisodeCleanupAlgorithm().performCleanup(context);
    }

    /**
     * Looks for non-downloaded episodes in the queue or list of unread items and request a download if
     * 1. Network is available
     * 2. The device is charging or the user allows auto download on battery
     * 3. There is free space in the episode cache
     * This method is executed on an internal single thread executor.
     *
     * @param context  Used for accessing the DB.
     * @return A Future that can be used for waiting for the methods completion.
     */
    public static Future<?> autodownloadUndownloadedItems(final Context context) {
        Log.d("EpisodeDownloadService", "autodownloadUndownloadedItems");
        return autodownloadExec.submit(downloadAlgorithm.autoDownloadUndownloadedItems(context));
    }

    /**
     * For testing purpose only.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setDownloadAlgorithm(AutomaticDownloadAlgorithm newDownloadAlgorithm) {
        downloadAlgorithm = newDownloadAlgorithm;
    }
}
