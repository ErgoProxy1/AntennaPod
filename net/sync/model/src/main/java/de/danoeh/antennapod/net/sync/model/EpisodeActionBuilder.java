package de.danoeh.antennapod.net.sync.model;

import android.text.TextUtils;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import de.danoeh.antennapod.model.feed.FeedItem;

public class EpisodeActionBuilder {

    private static final String PATTERN_ISO_DATEFORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    /**
     * Create an episode action object from JSON representation. Mandatory fields are "podcast",
     * "episode", "action", and "timestamp".
     *
     * @param object JSON representation
     * @return episode action object, or null if mandatory values are missing
     */
    public static EpisodeAction readFromJsonObject(JSONObject object) {
        String podcast = object.optString("podcast", null);
        String episode = object.optString("episode", null);
        String guid = object.optString("guid", null);
        int started = object.optInt("started", -1);
        int position = object.optInt("position", -1);
        int total = object.optInt("total", -1);

        EpisodeAction.Action action = createAction(object, podcast, episode);
        Date timestamp = createParsedDate(object);
        if(action == null || timestamp == null) {
            return null;
        }
        Builder builder = new Builder(podcast, episode, action, timestamp, guid);
        if (isPlayable(action, started, position, total)) {
            builder.started(started).position(position).total(total);
        }
        return builder.build();
    }

    private static EpisodeAction.Action createAction(JSONObject object, String podcast, String episode) {
        String actionString = object.optString("action", null);
        if (TextUtils.isEmpty(podcast) || TextUtils.isEmpty(episode) || TextUtils.isEmpty(actionString)) {
            return null;
        }
        try {
            return EpisodeAction.Action.valueOf(actionString.toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Date createParsedDate(JSONObject object){
        String utcTimestamp = object.optString("timestamp", null);
        if (!TextUtils.isEmpty(utcTimestamp)) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(PATTERN_ISO_DATEFORMAT, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                return parser.parse(utcTimestamp);
            } catch (ParseException e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

    private static boolean isPlayable(EpisodeAction.Action action, int started, int position, int total){
        return action == EpisodeAction.Action.PLAY && started >= 0 && position > 0 && total > 0;
    }

    public static class Builder {

        // mandatory
        public final String podcast;
        public final String episode;
        public final EpisodeAction.Action action;

        // optional
        public Date timestamp;
        public int started = -1;
        public int position = -1;
        public int total = -1;
        public String guid;

        public Builder(FeedItem item, EpisodeAction.Action action) {
            this(item.getFeed().getDownload_url(), item.getMedia().getDownload_url(), action);
            this.guid(item.getItemIdentifier());
        }

        public Builder(String podcast, String episode, EpisodeAction.Action action) {
            this.podcast = podcast;
            this.episode = episode;
            this.action = action;
        }

        public Builder(String podcast, String episode, EpisodeAction.Action action, Date timestamp, String guid) {
            this.podcast = podcast;
            this.episode = episode;
            this.action = action;
            this.timestamp = timestamp;
            if (!TextUtils.isEmpty(guid)) {
                this.guid = guid;
            }
        }

        public Builder timestamp(Date timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder guid(String guid) {
            this.guid = guid;
            return this;
        }

        public Builder currentTimestamp() {
            return timestamp(new Date());
        }

        public Builder started(int seconds) {
            if (action == EpisodeAction.Action.PLAY) {
                this.started = seconds;
            }
            return this;
        }

        public Builder position(int seconds) {
            if (action == EpisodeAction.Action.PLAY) {
                this.position = seconds;
            }
            return this;
        }

        public Builder total(int seconds) {
            if (action == EpisodeAction.Action.PLAY) {
                this.total = seconds;
            }
            return this;
        }

        public EpisodeAction build() {
            return new EpisodeAction(this);
        }

    }

}
