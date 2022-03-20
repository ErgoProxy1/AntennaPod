package de.danoeh.antennapod.net.sync.model;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import de.danoeh.antennapod.model.feed.FeedItem;

public class EpisodeAction {
    private static final String TAG = "EpisodeAction";
    public static final Action NEW = Action.NEW;
    public static final Action DOWNLOAD = Action.DOWNLOAD;
    public static final Action PLAY = Action.PLAY;
    public static final Action DELETE = Action.DELETE;

    private final String podcast;
    private final String episode;
    private final String guid;
    private final Action action;
    private final Date timestamp;
    private final int started;
    private final int position;
    private final int total;

    public EpisodeAction(EpisodeActionBuilder.Builder builder) {
        this.podcast = builder.podcast;
        this.episode = builder.episode;
        this.guid = builder.guid;
        this.action = builder.action;
        this.timestamp = builder.timestamp;
        this.started = builder.started;
        this.position = builder.position;
        this.total = builder.total;
    }

    public String getPodcast() {
        return this.podcast;
    }

    public String getEpisode() {
        return this.episode;
    }

    public String getGuid() {
        return this.guid;
    }

    public Action getAction() {
        return this.action;
    }

    private String getActionString() {
        return this.action.name().toLowerCase(Locale.US);
    }

    public Date getTimestamp() {
        return this.timestamp;
    }

    /**
     * Returns the position (in seconds) at which the client started playback.
     *
     * @return start position (in seconds)
     */
    public int getStarted() {
        return this.started;
    }

    /**
     * Returns the position (in seconds) at which the client stopped playback.
     *
     * @return stop position (in seconds)
     */
    public int getPosition() {
        return this.position;
    }

    /**
     * Returns the total length of the file in seconds.
     *
     * @return total length in seconds
     */
    public int getTotal() {
        return this.total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EpisodeAction)) {
            return false;
        }

        EpisodeAction that = (EpisodeAction) o;
        return started == that.started
                && position == that.position
                && total == that.total
                && action != that.action
                && Objects.equals(podcast, that.podcast)
                && Objects.equals(episode, that.episode)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(guid, that.guid);
    }

    @Override
    public int hashCode() {
        int result = podcast != null ? podcast.hashCode() : 0;
        result = 31 * result + (episode != null ? episode.hashCode() : 0);
        result = 31 * result + (guid != null ? guid.hashCode() : 0);
        result = 31 * result + (action != null ? action.hashCode() : 0);
        result = 31 * result + (timestamp != null ? timestamp.hashCode() : 0);
        result = 31 * result + started;
        result = 31 * result + position;
        result = 31 * result + total;
        return result;
    }

    /**
     * Returns a JSON object representation of this object.
     *
     * @return JSON object representation, or null if the object is invalid
     */
    public JSONObject writeToJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.putOpt("podcast", this.podcast);
            obj.putOpt("episode", this.episode);
            obj.putOpt("guid", this.guid);
            obj.put("action", this.getActionString());
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
            obj.put("timestamp", formatter.format(this.timestamp));
            if (this.getAction() == Action.PLAY) {
                obj.put("started", this.started);
                obj.put("position", this.position);
                obj.put("total", this.total);
            }
        } catch (JSONException e) {
            Log.e(TAG, "writeToJSONObject(): " + e.getMessage());
            return null;
        }
        return obj;
    }

    @NonNull
    @Override
    public String toString() {
        return "EpisodeAction{"
                + "podcast='" + podcast + '\''
                + ", episode='" + episode + '\''
                + ", guid='" + guid + '\''
                + ", action=" + action
                + ", timestamp=" + timestamp
                + ", started=" + started
                + ", position=" + position
                + ", total=" + total
                + '}';
    }

    public enum Action {
        NEW, DOWNLOAD, PLAY, DELETE
    }

}
