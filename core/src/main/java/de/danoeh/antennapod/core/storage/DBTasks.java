package de.danoeh.antennapod.core.storage;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import de.danoeh.antennapod.core.service.download.DownloadRequest;
import de.danoeh.antennapod.core.service.download.DownloadRequestCreator;
import de.danoeh.antennapod.core.service.download.DownloadService;
import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import de.danoeh.antennapod.core.R;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.service.download.DownloadStatus;
import de.danoeh.antennapod.core.storage.mapper.FeedCursorMapper;
import de.danoeh.antennapod.core.sync.SyncService;
import de.danoeh.antennapod.core.sync.queue.SynchronizationQueueSink;
import de.danoeh.antennapod.core.util.DownloadError;
import de.danoeh.antennapod.core.util.LongList;
import de.danoeh.antennapod.core.util.comparator.FeedItemPubdateComparator;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.sync.model.EpisodeAction;
import de.danoeh.antennapod.net.sync.model.EpisodeActionBuilder;

/**
 * Provides methods for doing common tasks that use DBReader and DBWriter.
 */
public final class DBTasks {
    private static final String TAG = "DBTasks";

    private DBTasks() {
    }

    /**
     * Removes the feed with the given download url. This method should NOT be executed on the GUI thread.
     *
     * @param context     Used for accessing the db
     * @param downloadUrl URL of the feed.
     */
    public static void removeFeedWithDownloadUrl(Context context, String downloadUrl) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        Cursor cursor = adapter.getFeedCursorDownloadUrls();
        long feedID = 0;
        if (cursor.moveToFirst()) {
            do {
                if (cursor.getString(1).equals(downloadUrl)) {
                    feedID = cursor.getLong(0);
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        adapter.close();

        if (feedID != 0) {
            try {
                DBWriter.deleteFeed(context, feedID).get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        } else {
            Log.w(TAG, "removeFeedWithDownloadUrl: Could not find feed with url: " + downloadUrl);
        }
    }

    /**
     * Notifies the database about a missing FeedMedia file. This method will correct the FeedMedia object's
     * values in the DB and send a FeedItemEvent.
     */
    public static void notifyMissingFeedMediaFile(final Context context, final FeedMedia media) {
        Log.i(TAG, "The feedmanager was notified about a missing episode. It will update its database now.");
        media.setDownloaded(false);
        media.setFile_url(null);
        DBWriter.setFeedMedia(media);
        EventBus.getDefault().post(FeedItemEvent.deletedMedia(media.getItem()));
        EventBus.getDefault().post(new MessageEvent(context.getString(R.string.error_file_not_found)));
    }

    public static List<FeedItem> enqueueFeedItemsToDownload(final Context context,
                       List<FeedItem> items) throws InterruptedException, ExecutionException {
        List<FeedItem> itemsToEnqueue = new ArrayList<>();
        if (UserPreferences.enqueueDownloadedEpisodes()) {
            LongList queueIDList = DBReader.getQueueIDList();
            for (FeedItem item : items) {
                if (!queueIDList.contains(item.getId())) {
                    itemsToEnqueue.add(item);
                }
            }
            DBWriter.addQueueItem(context, false, itemsToEnqueue.toArray(new FeedItem[0])).get();
        }
        return itemsToEnqueue;
    }

    private static Feed searchFeedByIdentifyingValueOrID(PodDBAdapter adapter,
                                                         Feed feed) {
        if (feed.getId() != 0) {
            return DBReader.getFeed(feed.getId());
        } else {
            List<Feed> feeds = DBReader.getFeedList();
            for (Feed f : feeds) {
                if (f.getIdentifyingValue().equals(feed.getIdentifyingValue())) {
                    f.setItems(DBReader.getFeedItemList(f));
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Get a FeedItem by its identifying value.
     */
    private static FeedItem searchFeedItemByIdentifyingValue(List<FeedItem> items, FeedItem searchItem) {
        for (FeedItem item : items) {
            if (TextUtils.equals(item.getIdentifyingValue(), searchItem.getIdentifyingValue())) {
                return item;
            }
        }
        return null;
    }

    /**
     * Guess if one of the items could actually mean the searched item, even if it uses another identifying value.
     * This is to work around podcasters breaking their GUIDs.
     */
    private static FeedItem searchFeedItemGuessDuplicate(List<FeedItem> items, FeedItem searchItem) {
        for (FeedItem item : items) {
            if (FeedItemDuplicateGuesser.seemDuplicates(item, searchItem)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Adds new Feeds to the database or updates the old versions if they already exists. If another Feed with the same
     * identifying value already exists, this method will add new FeedItems from the new Feed to the existing Feed.
     * These FeedItems will be marked as unread with the exception of the most recent FeedItem.
     * <p/>
     * This method can update multiple feeds at once. Submitting a feed twice in the same method call can result in undefined behavior.
     * <p/>
     * This method should NOT be executed on the GUI thread.
     *
     * @param context Used for accessing the DB.
     * @param newFeed The new Feed object.
     * @param removeUnlistedItems The item list in the new Feed object is considered to be exhaustive.
     *                            I.e. items are removed from the database if they are not in this item list.
     * @return The updated Feed from the database if it already existed, or the new Feed from the parameters otherwise.
     */
    public static synchronized Feed updateFeed(Context context, Feed newFeed, boolean removeUnlistedItems) {
        Feed resultFeed;
        List<FeedItem> unlistedItems = new ArrayList<>();

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        // Look up feed in the feedslist
        final Feed savedFeed = searchFeedByIdentifyingValueOrID(adapter, newFeed);
        if (savedFeed == null) {
            Log.d(TAG, "Found no existing Feed with title "
                            + newFeed.getTitle() + ". Adding as new one.");

            // Add a new Feed
            // all new feeds will have the most recent item marked as unplayed
            FeedItem mostRecent = newFeed.getMostRecentItem();
            if (mostRecent != null) {
                mostRecent.setNew();
            }

            resultFeed = newFeed;
        } else {
            Log.d(TAG, "Feed with title " + newFeed.getTitle()
                        + " already exists. Syncing new with existing one.");

            Collections.sort(newFeed.getItems(), new FeedItemPubdateComparator());

            if (newFeed.getPageNr() == savedFeed.getPageNr()) {
                if (savedFeed.compareWithOther(newFeed)) {
                    Log.d(TAG, "Feed has updated attribute values. Updating old feed's attributes");
                    savedFeed.updateFromOther(newFeed);
                }
            } else {
                Log.d(TAG, "New feed has a higher page number.");
                savedFeed.setNextPageLink(newFeed.getNextPageLink());
            }
            if (savedFeed.getPreferences().compareWithOther(newFeed.getPreferences())) {
                Log.d(TAG, "Feed has updated preferences. Updating old feed's preferences");
                savedFeed.getPreferences().updateFromOther(newFeed.getPreferences());
            }

            // get the most recent date now, before we start changing the list
            FeedItem priorMostRecent = savedFeed.getMostRecentItem();
            Date priorMostRecentDate = null;
            if (priorMostRecent != null) {
                priorMostRecentDate = priorMostRecent.getPubDate();
            }

            // Look for new or updated Items
            for (int idx = 0; idx < newFeed.getItems().size(); idx++) {
                final FeedItem item = newFeed.getItems().get(idx);

                FeedItem possibleDuplicate = searchFeedItemGuessDuplicate(newFeed.getItems(), item);
                if (!newFeed.isLocalFeed() && possibleDuplicate != null && item != possibleDuplicate) {
                    // Canonical episode is the first one returned (usually oldest)
                    DBWriter.addDownloadStatus(new DownloadStatus(savedFeed,
                            item.getTitle(), DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE, false,
                            "The podcast host appears to have added the same episode twice. "
                                    + "AntennaPod still refreshed the feed and attempted to repair it."
                                    + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(item)
                                    + "\n\nSecond episode that is also in the feed:\n"
                                    + duplicateEpisodeDetails(possibleDuplicate), false));
                    continue;
                }

                FeedItem oldItem = searchFeedItemByIdentifyingValue(savedFeed.getItems(), item);
                if (!newFeed.isLocalFeed() && oldItem == null) {
                    oldItem = searchFeedItemGuessDuplicate(savedFeed.getItems(), item);
                    if (oldItem != null) {
                        Log.d(TAG, "Repaired duplicate: " + oldItem + ", " + item);
                        DBWriter.addDownloadStatus(new DownloadStatus(savedFeed,
                                item.getTitle(), DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE, false,
                                "The podcast host changed the ID of an existing episode instead of just "
                                        + "updating the episode itself. AntennaPod still refreshed the feed and "
                                        + "attempted to repair it."
                                        + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(oldItem)
                                        + "\n\nNow the feed contains:\n" + duplicateEpisodeDetails(item), false));
                        oldItem.setItemIdentifier(item.getItemIdentifier());

                        if (oldItem.isPlayed() && oldItem.getMedia() != null) {
                            EpisodeAction action = new EpisodeActionBuilder.Builder(oldItem, EpisodeAction.PLAY)
                                    .currentTimestamp()
                                    .started(oldItem.getMedia().getDuration() / 1000)
                                    .position(oldItem.getMedia().getDuration() / 1000)
                                    .total(oldItem.getMedia().getDuration() / 1000)
                                    .build();
                            SynchronizationQueueSink.enqueueEpisodeActionIfSynchronizationIsActive(context, action);
                        }
                    }
                }

                if (oldItem != null) {
                    oldItem.updateFromOther(item);
                } else {
                    // item is new
                    item.setFeed(savedFeed);

                    if (idx >= savedFeed.getItems().size()) {
                        savedFeed.getItems().add(item);
                    } else {
                        savedFeed.getItems().add(idx, item);
                    }

                    // only mark the item new if it was published after or at the same time
                    // as the most recent item
                    // (if the most recent date is null then we can assume there are no items
                    // and this is the first, hence 'new')
                    // New items that do not have a pubDate set are always marked as new
                    if (item.getPubDate() == null || priorMostRecentDate == null
                            || priorMostRecentDate.before(item.getPubDate())
                            || priorMostRecentDate.equals(item.getPubDate())) {
                        Log.d(TAG, "Marking item published on " + item.getPubDate()
                                + " new, prior most recent date = " + priorMostRecentDate);
                        item.setNew();
                    }
                }
            }

            // identify items to be removed
            if (removeUnlistedItems) {
                Iterator<FeedItem> it = savedFeed.getItems().iterator();
                while (it.hasNext()) {
                    FeedItem feedItem = it.next();
                    if (searchFeedItemByIdentifyingValue(newFeed.getItems(), feedItem) == null) {
                        unlistedItems.add(feedItem);
                        it.remove();
                    }
                }
            }

            // update attributes
            savedFeed.setLastUpdate(newFeed.getLastUpdate());
            savedFeed.setType(newFeed.getType());
            savedFeed.setLastUpdateFailed(false);

            resultFeed = savedFeed;
        }

        try {
            if (savedFeed == null) {
                DBWriter.addNewFeed(context, newFeed).get();
                // Update with default values that are set in database
                resultFeed = searchFeedByIdentifyingValueOrID(adapter, newFeed);
            } else {
                DBWriter.setCompleteFeed(savedFeed).get();
            }
            if (removeUnlistedItems) {
                DBWriter.deleteFeedItems(context, unlistedItems).get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        adapter.close();

        if (savedFeed != null) {
            EventBus.getDefault().post(new FeedListUpdateEvent(savedFeed));
        } else {
            EventBus.getDefault().post(new FeedListUpdateEvent(Collections.emptyList()));
        }

        return resultFeed;
    }

    private static String duplicateEpisodeDetails(FeedItem item) {
        return "Title: " + item.getTitle()
                + "\nID: " + item.getItemIdentifier()
                + ((item.getMedia() == null) ? "" : "\nURL: " + item.getMedia().getDownload_url());
    }

    /**
     * Searches the FeedItems of a specific Feed for a given string.
     *
     * @param context Used for accessing the DB.
     * @param feedID  The id of the feed whose items should be searched.
     * @param query   The search string.
     * @return A FutureTask object that executes the search request
     *         and returns the search result as a List of FeedItems.
     */
    public static FutureTask<List<FeedItem>> searchFeedItems(final Context context,
                                                                 final long feedID, final String query) {
        return new FutureTask<>(new QueryTask<List<FeedItem>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor searchResult = adapter.searchItems(feedID, query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(searchResult);
                DBReader.loadAdditionalFeedItemListData(items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    public static FutureTask<List<Feed>> searchFeeds(final Context context, final String query) {
        return new FutureTask<>(new QueryTask<List<Feed>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor cursor = adapter.searchFeeds(query);
                List<Feed> items = new ArrayList<>();
                if (cursor.moveToFirst()) {
                    do {
                        items.add(FeedCursorMapper.convert(cursor));
                    } while (cursor.moveToNext());
                }
                setResult(items);
                cursor.close();
            }
        });
    }

    /**
     * A runnable which should be used for database queries. The onCompletion
     * method is executed on the database executor to handle Cursors correctly.
     * This class automatically creates a PodDBAdapter object and closes it when
     * it is no longer in use.
     */
    abstract static class QueryTask<T> implements Callable<T> {
        private T result;

        public QueryTask(Context context) {
        }

        @Override
        public T call() throws Exception {
            PodDBAdapter adapter = PodDBAdapter.getInstance();
            adapter.open();
            execute(adapter);
            adapter.close();
            return result;
        }

        public abstract void execute(PodDBAdapter adapter);

        void setResult(T result) {
            this.result = result;
        }
    }
}
