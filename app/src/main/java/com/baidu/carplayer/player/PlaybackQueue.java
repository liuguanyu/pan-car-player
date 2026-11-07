package com.baidu.carplayer.player;

import com.baidu.carplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放队列与导航：持有歌曲列表、当前下标和随机播放序列。
 * 纯 Java，不依赖播放器与 Android 框架；导航只计算目标下标，由调用方去播放。
 */
public final class PlaybackQueue {
    public enum Mode { ORDER, RANDOM, SINGLE }

    /** 导航结果：目标下标，或"不切换"。 */
    public static final int STAY = -1;

    private final Random random;
    private List<Song> songs = Collections.emptyList();
    private int index = 0;
    private Mode mode = Mode.ORDER;
    private List<Integer> shuffleOrder;
    private int shufflePosition = -1;

    public PlaybackQueue() {
        this(new Random());
    }

    public PlaybackQueue(Random random) {
        this.random = random;
    }

    /** 替换队列并重置洗牌序列；下标保持在有效范围内。 */
    public void setSongs(List<Song> songs) {
        this.songs = songs == null ? Collections.<Song>emptyList() : new ArrayList<>(songs);
        shuffleOrder = null;
        shufflePosition = -1;
        if (index >= this.songs.size()) {
            index = Math.max(0, this.songs.size() - 1);
        }
    }

    public List<Song> getSongs() {
        return songs;
    }

    public int size() {
        return songs.size();
    }

    public boolean isEmpty() {
        return songs.isEmpty();
    }

    public int getIndex() {
        return index;
    }

    public Song current() {
        return index >= 0 && index < songs.size() ? songs.get(index) : null;
    }

    public Song at(int position) {
        return position >= 0 && position < songs.size() ? songs.get(position) : null;
    }

    /** 直接定位；越界时返回 false 且不改变状态。 */
    public boolean moveTo(int position) {
        if (position < 0 || position >= songs.size()) {
            return false;
        }
        index = position;
        return true;
    }

    /** 按稳定 ID 定位，找不到时保持原位。 */
    public boolean moveToSongId(long songId) {
        int found = Song.findSongPosition(songs, songId);
        return found >= 0 && moveTo(found);
    }

    public Mode getMode() {
        return mode;
    }

    /** 切换模式；进入随机模式时重新洗牌，并把当前歌曲放在序列首位。 */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.ORDER : mode;
        if (this.mode == Mode.RANDOM) {
            generateShuffleOrder();
        }
    }

    /**
     * 下一首的目标下标。单曲循环返回 {@link #STAY}；随机模式一轮播完会重新洗牌，
     * 并避免新序列以刚播完的那首开头。返回前已经把内部下标更新为目标。
     */
    public int next() {
        if (songs.isEmpty()) {
            return STAY;
        }
        switch (mode) {
            case RANDOM:
                if (shuffleOrder == null || shuffleOrder.size() != songs.size()) {
                    generateShuffleOrder();
                }
                shufflePosition++;
                if (shufflePosition >= shuffleOrder.size()) {
                    int lastPlayed = shuffleOrder.get(shuffleOrder.size() - 1);
                    generateShuffleOrder();
                    if (shuffleOrder.size() > 1 && shuffleOrder.get(0) == lastPlayed) {
                        // 首尾衔接时不立刻重复同一首
                        int swapPos = 1 + random.nextInt(shuffleOrder.size() - 1);
                        Collections.swap(shuffleOrder, 0, swapPos);
                    }
                    shufflePosition = 0;
                }
                index = shuffleOrder.get(shufflePosition);
                return index;
            case SINGLE:
                return STAY;
            case ORDER:
            default:
                index = (index + 1) % songs.size();
                return index;
        }
    }

    /** 上一首的目标下标；语义与 {@link #next()} 对称。 */
    public int previous() {
        if (songs.isEmpty()) {
            return STAY;
        }
        switch (mode) {
            case RANDOM:
                if (shuffleOrder == null || shuffleOrder.isEmpty()) {
                    generateShuffleOrder();
                }
                if (shufflePosition > 0) {
                    shufflePosition--;
                    index = shuffleOrder.get(shufflePosition);
                }
                // 已在序列首位：保持当前歌曲
                return index;
            case SINGLE:
                return STAY;
            case ORDER:
            default:
                index = (index - 1 + songs.size()) % songs.size();
                return index;
        }
    }

    /** 当前随机序列（仅测试与日志使用）。 */
    List<Integer> shuffleOrderSnapshot() {
        return shuffleOrder == null ? null : new ArrayList<>(shuffleOrder);
    }

    /** Fisher-Yates 洗牌，并把随机模式切换/恢复前的当前歌曲放到序列首位。 */
    private void generateShuffleOrder() {
        if (songs.isEmpty()) {
            shuffleOrder = null;
            shufflePosition = -1;
            return;
        }
        int size = songs.size();
        List<Integer> order = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        for (int i = size - 1; i > 0; i--) {
            Collections.swap(order, i, random.nextInt(i + 1));
        }
        if (index >= 0 && index < size) {
            int pos = order.indexOf(index);
            if (pos > 0) {
                Collections.swap(order, 0, pos);
            }
        }
        shuffleOrder = order;
        shufflePosition = 0;
    }
}
