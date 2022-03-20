package de.danoeh.antennapod.core.util;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import de.danoeh.antennapod.core.service.download.DownloadRequest;
import de.danoeh.antennapod.core.service.download.DownloadRequestCreator;
import de.danoeh.antennapod.core.service.download.DownloadService;
import de.danoeh.antennapod.core.sync.SyncService;
import de.danoeh.antennapod.model.feed.Feed;

public class FeedUtils {

    private static final String PREF_NAME = "FeedPreferences";
    private static final String PREF_LAST_REFRESH = "last_refresh";

    /**
     * Refreshes all feeds.
     * It must not be from the main thread.
     * This method might ignore subsequent calls if it is still
     * enqueuing Feeds for download from a previous call
     *
     * @param context  Might be used for accessing the database
     * @param initiatedByUser a boolean indicating if the refresh was triggered by user action.
     */
    public static void refreshAllFeeds(final Context context, boolean initiatedByUser) {
        DownloadService.refreshAllFeeds(context, initiatedByUser);

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putLong(PREF_LAST_REFRESH, System.currentTimeMillis()).apply();

        SyncService.sync(context);
        // Note: automatic download of episodes will be done but not here.
        // Instead it is done after all feeds have been refreshed (asynchronously),
        // in DownloadService.onDestroy()
        // See Issue #2577 for the details of the rationale
    }

    /**
     * Queues the next page of this Feed for download. The given Feed has to be a paged
     * Feed (isPaged()=true) and must contain a nextPageLink.
     *
     * @param context      Used for requesting the download.
     * @param feed         The feed whose next page should be loaded.
     * @param loadAllPages True if any subsequent pages should also be loaded, false otherwise.
     */
    public static void loadNextPageOfFeed(final Context context, Feed feed, boolean loadAllPages) {
        if (feed.isPaged() && feed.getNextPageLink() != null) {
            int pageNr = feed.getPageNr() + 1;
            Feed nextFeed = new Feed(feed.getNextPageLink(), null, feed.getTitle() + "(" + pageNr + ")");
            nextFeed.setPageNr(pageNr);
            nextFeed.setPaged(true);
            nextFeed.setId(feed.getId());

            DownloadRequest.Builder builder = DownloadRequestCreator.create(nextFeed);
            builder.loadAllPages(loadAllPages);
            DownloadService.download(context, false, builder.build());
        } else {
            Log.e("loadNextPageOfFeed", "Feed was either not paged or contained no nextPageLink");
        }
    }

    public static void forceRefreshFeed(Context context, Feed feed, boolean initiatedByUser) {
        forceRefreshFeed(context, feed, false, initiatedByUser);
    }

    public static void forceRefreshCompleteFeed(final Context context, final Feed feed) {
        forceRefreshFeed(context, feed, true, true);
    }

    private static void forceRefreshFeed(Context context, Feed feed, boolean loadAllPages, boolean initiatedByUser) {
        DownloadRequest.Builder builder = DownloadRequestCreator.create(feed);
        builder.setInitiatedByUser(initiatedByUser);
        builder.setForce(true);
        builder.loadAllPages(loadAllPages);
        DownloadService.download(context, false, builder.build());
    }
}
