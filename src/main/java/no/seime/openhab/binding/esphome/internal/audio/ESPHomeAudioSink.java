/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.audio;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.audio.AudioSinkAsync;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.StreamServed;
import org.openhab.core.audio.URLAudioStream;
import org.openhab.core.audio.UnsupportedAudioFormatException;
import org.openhab.core.library.types.PercentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerCommandRequest;
import io.esphome.api.MediaPlayerState;
import io.esphome.api.MediaPlayerStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

/**
 * An ESPHome {@link org.openhab.core.audio.AudioSink} implementation streaming audio to an ESPHome media player over
 * the native API.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class ESPHomeAudioSink extends AudioSinkAsync {

    private static final int STREAM_URL_TTL_SECONDS = 30;
    private static final Set<Class<? extends AudioStream>> SUPPORTED_STREAMS = Set.of(AudioStream.class);

    private final Logger logger = LoggerFactory.getLogger(ESPHomeAudioSink.class);

    private final ESPHomeHandler handler;
    private final AudioHTTPServer audioHTTPServer;

    private final String id;
    private final String label;
    private final String baseUrl;
    private final int mediaPlayerKey;
    private final String mediaPlayerObjectId;
    private final Set<AudioFormat> supportedFormats;

    private final Object playbackLock = new Object();
    private @Nullable AudioStream pendingStream;
    private boolean announcementStarted;
    private boolean playbackStarted;
    private @Nullable ScheduledFuture<?> pendingStreamTimeout;

    private volatile @Nullable MediaPlayerStateResponse state;

    public ESPHomeAudioSink(ESPHomeHandler handler, AudioHTTPServer audioHTTPServer,
            ListEntitiesMediaPlayerResponse mediaPlayer, Set<AudioFormat> supportedFormats, String baseUrl) {
        this.handler = handler;
        this.audioHTTPServer = audioHTTPServer;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.mediaPlayerKey = mediaPlayer.getKey();
        this.mediaPlayerObjectId = mediaPlayer.getObjectId();
        this.supportedFormats = supportedFormats;
        String id = handler.getThing().getUID() + ":sink";
        if (!mediaPlayerObjectId.isBlank()) {
            id += ":" + mediaPlayerObjectId;
        }
        this.id = id;
        String thingLabel = handler.getThing().getLabel() != null ? handler.getThing().getLabel()
                : handler.getThing().getUID().getId();
        this.label = "ESPHome " + thingLabel + " (" + mediaPlayer.getName() + ")";
    }

    public void updateState(@Nullable MediaPlayerStateResponse state) {
        this.state = state;
        if (state == null) {
            return;
        }
        // Track URL stream playback and only signal completion upon playback end
        synchronized (playbackLock) {
            if (pendingStream == null) {
                return;
            }
            MediaPlayerState playerState = state.getState();
            if (playerState == MediaPlayerState.MEDIA_PLAYER_STATE_ANNOUNCING) {
                announcementStarted = true;
            } else if (playerState == MediaPlayerState.MEDIA_PLAYER_STATE_PLAYING) {
                playbackStarted = true;
            } else if (announcementStarted) {
                completePendingStream();
            } else if (playbackStarted && (playerState == MediaPlayerState.MEDIA_PLAYER_STATE_IDLE
                    || playerState == MediaPlayerState.MEDIA_PLAYER_STATE_OFF
                    || playerState == MediaPlayerState.MEDIA_PLAYER_STATE_PAUSED)) {
                completePendingStream();
            }
        }
    }

    public void dispose() {
        synchronized (playbackLock) {
            completePendingStream();
        }
    }

    /**
     * To be called on playback completion of the pending {@link AudioStream}.
     */
    private void completePendingStream() {
        cancelPendingStreamTimeout();
        AudioStream stream = pendingStream;
        if (stream != null) {
            pendingStream = null;
            announcementStarted = false;
            playbackStarted = false;
            playbackFinished(stream);
        }
    }

    private void cancelPendingStreamTimeout() {
        ScheduledFuture<?> timeout = pendingStreamTimeout;
        if (timeout != null) {
            timeout.cancel(false);
            pendingStreamTimeout = null;
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public @Nullable String getLabel(@Nullable Locale locale) {
        return label;
    }

    @Override
    protected void processAsynchronously(@Nullable AudioStream audioStream) throws UnsupportedAudioFormatException {
        if (audioStream == null) {
            synchronized (playbackLock) {
                completePendingStream();
            }
            try {
                handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasCommand(true)
                        .setHasAnnouncement(true).setAnnouncement(true)
                        .setCommand(io.esphome.api.MediaPlayerCommand.MEDIA_PLAYER_COMMAND_STOP).build());
            } catch (ProtocolAPIError e) {
                throw new IllegalStateException("Failed to stop ESPHome media player playback", e);
            }
            return;
        }

        if (!supports(audioStream.getFormat())) {
            throw new UnsupportedAudioFormatException("ESPHome media player does not support stream format",
                    audioStream.getFormat());
        }

        try {
            if (audioStream instanceof URLAudioStream urlAudioStream) {
                // For URLAudioStream, track playback completion by listening to playback state updates from the ESPHome
                // device in updateState()
                synchronized (playbackLock) {
                    completePendingStream();
                    pendingStream = audioStream;
                    announcementStarted = false;
                    playbackStarted = false;
                    pendingStreamTimeout = handler.schedule(() -> {
                        synchronized (playbackLock) {
                            if (pendingStream == audioStream) {
                                logger.debug(
                                        "[{}] Timeout waiting for ESPHome media player {} announcement to complete",
                                        handler.getLogPrefix(), mediaPlayerObjectId);
                                completePendingStream();
                            }
                        }
                    }, STREAM_URL_TTL_SECONDS, TimeUnit.SECONDS);
                }
                handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasMediaUrl(true)
                        .setMediaUrl(urlAudioStream.getURL()).setHasAnnouncement(true).setAnnouncement(true).build());
            } else {
                StreamServed servedStream = audioHTTPServer.serve(audioStream, STREAM_URL_TTL_SECONDS, false);
                String absoluteUrl = URI.create(baseUrl + servedStream.url()).toString();

                MediaPlayerCommandRequest request = MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey)
                        .setHasMediaUrl(true).setMediaUrl(absoluteUrl).setHasAnnouncement(true).setAnnouncement(true)
                        .build();
                handler.sendMessage(request);
                servedStream.playEnd().whenComplete((ignored, error) -> {
                    if (error != null) {
                        runnableByAudioStream.remove(audioStream).completeExceptionally(error);
                    } else {
                        playbackFinished(audioStream);
                    }
                });
            }
        } catch (IOException | ProtocolAPIError e) {
            synchronized (playbackLock) {
                if (pendingStream == audioStream) {
                    completePendingStream();
                }
            }
            logger.debug("[{}] Unable to send audio stream to ESPHome media player {}", handler.getLogPrefix(),
                    mediaPlayerObjectId, e);
            throw new IllegalStateException("Unable to send audio stream to ESPHome media player", e);
        }
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return supportedFormats;
    }

    @Override
    public Set<Class<? extends AudioStream>> getSupportedStreams() {
        return SUPPORTED_STREAMS;
    }

    @Override
    public PercentType getVolume() throws IOException {
        MediaPlayerStateResponse currentState = state;
        if (currentState == null) {
            throw new IOException("ESPHome media player has not reported its current state yet");
        }
        return new PercentType(Math.round(currentState.getVolume() * 100f));
    }

    @Override
    public void setVolume(PercentType volume) throws IOException {
        try {
            handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasVolume(true)
                    .setVolume(volume.floatValue() / 100f).build());
        } catch (ProtocolAPIError e) {
            throw new IOException("Failed to set ESPHome media player volume", e);
        }
    }

    private boolean supports(AudioFormat actualFormat) {
        return supportedFormats.stream()
                .anyMatch(format -> format.isCompatible(actualFormat) || actualFormat.isCompatible(format));
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
