package com.baidu.carplayer.player;

/**
 * 跟踪播放页当前应展示哪首歌的歌词。
 * 只有当播放状态中的歌曲 ID 变化时才发起新请求，结果必须与发起时的歌曲和代次一致才会被采纳。
 */
public final class LyricsSession {

    /** 一次歌词加载请求的身份。 */
    public static final class Ticket {
        public final long generation;
        public final long songId;

        Ticket(long generation, long songId) {
            this.generation = generation;
            this.songId = songId;
        }
    }

    private long songId = -1L;
    private long generation;
    private boolean closed;

    /**
     * 收到播放状态时调用。歌曲变化时返回新的请求凭据，否则返回 null。
     * songId <= 0 表示没有当前歌曲，此时清空当前歌曲并使旧请求失效。
     */
    public Ticket onSongChanged(long newSongId) {
        if (closed || newSongId == songId) {
            return null;
        }
        songId = newSongId;
        generation++;
        return newSongId > 0 ? new Ticket(generation, newSongId) : null;
    }

    /** 歌词结果是否仍属于当前歌曲。 */
    public boolean accepts(Ticket ticket) {
        return ticket != null && !closed && ticket.generation == generation && ticket.songId == songId;
    }

    public long getSongId() {
        return songId;
    }

    /** 页面不可见时调用：旧请求失效，重新可见时会为当前歌曲重新加载。 */
    public void invalidate() {
        generation++;
        songId = -1L;
    }

    public void close() {
        closed = true;
        invalidate();
    }
}
