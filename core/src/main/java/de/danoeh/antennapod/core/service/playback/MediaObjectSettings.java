package de.danoeh.antennapod.core.service.playback;

public class MediaObjectSettings {
    final boolean forceReset;
    final boolean stream;
    final boolean startWhenPrepared;
    final boolean prepareImmediately;

    public MediaObjectSettings(boolean forceReset, boolean stream, boolean startWhenPrepared, boolean prepareImmediately){
        this.forceReset = forceReset;
        this.stream = stream;
        this.startWhenPrepared = startWhenPrepared;
        this.prepareImmediately = prepareImmediately;
    }
}
