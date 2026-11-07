package com.baidu.carplayer.playback;

import androidx.annotation.Nullable;

import java.util.Locale;

/** 当前曲目的解码格式，不可变；由 Service 在播放器就绪后随状态一起发布。 */
public final class AudioFormat {
    @Nullable
    public final String sampleMimeType;
    public final int sampleRate;
    public final long bitrate;
    public final int channelCount;

    public AudioFormat(@Nullable String sampleMimeType, int sampleRate, long bitrate, int channelCount) {
        this.sampleMimeType = sampleMimeType;
        this.sampleRate = sampleRate;
        this.bitrate = bitrate;
        this.channelCount = channelCount;
    }

    /** 播放页显示的格式名，例如 "FLAC"；未知时返回 null。 */
    @Nullable
    public String displayName() {
        if (sampleMimeType == null) return null;
        String format = sampleMimeType.toLowerCase(Locale.ROOT);
        if (format.contains("mpeg") || format.contains("mp3")) return "MP3";
        if (format.contains("flac")) return "FLAC";
        if (format.contains("alac")) return "ALAC";
        if (format.contains("aac")) return "AAC";
        if (format.contains("wav") || format.contains("pcm")) return "WAV";
        if (format.contains("ogg") || format.contains("vorbis") || format.contains("opus")) return "OGG";
        if (format.contains("wma") || format.contains("asf")) return "WMA";
        if (format.contains("m4a")) return "M4A";
        if (format.contains("ape")) return "APE";
        return sampleMimeType.toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AudioFormat)) return false;
        AudioFormat other = (AudioFormat) o;
        return sampleRate == other.sampleRate && bitrate == other.bitrate
                && channelCount == other.channelCount
                && (sampleMimeType == null ? other.sampleMimeType == null
                    : sampleMimeType.equals(other.sampleMimeType));
    }

    @Override
    public int hashCode() {
        int h = sampleMimeType == null ? 0 : sampleMimeType.hashCode();
        h = 31 * h + sampleRate;
        h = 31 * h + (int) (bitrate ^ (bitrate >>> 32));
        return 31 * h + channelCount;
    }
}
