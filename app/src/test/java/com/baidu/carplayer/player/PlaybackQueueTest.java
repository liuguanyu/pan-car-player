package com.baidu.carplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.Song;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public class PlaybackQueueTest {
    private static List<Song> songs(int n) {
        List<Song> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Song(i + 1, "p", "s" + (i + 1), "/m/" + i + ".mp3", 1L, 0L));
        }
        return list;
    }

    @Test
    public void orderModeWrapsBothDirections() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        assertEquals(0, q.getIndex());
        assertEquals(1, q.next());
        assertEquals(2, q.next());
        assertEquals("末尾回到第一首", 0, q.next());
        assertEquals("开头回到最后一首", 2, q.previous());
        assertEquals(1, q.previous());
    }

    @Test
    public void singleModeNeverMoves() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(4));
        q.moveTo(2);
        q.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(PlaybackQueue.STAY, q.next());
        assertEquals(PlaybackQueue.STAY, q.previous());
        assertEquals("当前下标不变", 2, q.getIndex());
    }

    @Test
    public void randomModePlaysEverySongExactlyOncePerRound() {
        PlaybackQueue q = new PlaybackQueue(new Random(42));
        q.setSongs(songs(6));
        q.moveTo(3);
        q.setMode(PlaybackQueue.Mode.RANDOM);
        assertEquals("随机模式从当前歌曲开始", 3, q.getIndex());

        List<Integer> seen = new ArrayList<>();
        seen.add(q.getIndex());
        for (int i = 0; i < 5; i++) {
            seen.add(q.next());
        }
        assertEquals(6, new HashSet<>(seen).size());
        assertEquals("一轮恰好覆盖全部歌曲", 6, seen.size());
    }

    @Test
    public void newRoundDoesNotRepeatLastSongImmediately() {
        PlaybackQueue q = new PlaybackQueue(new Random(7));
        q.setSongs(songs(5));
        q.setMode(PlaybackQueue.Mode.RANDOM);
        int last = q.getIndex();
        // 走完一整轮，记录最后一首
        for (int i = 0; i < 5; i++) {
            last = q.next();
        }
        // 下一首（新一轮第一首）不应等于刚播完的最后一首
        int first = q.next();
        assertFalse("新一轮首尾不应立刻重复", first == last);
    }

    @Test
    public void randomPreviousWalksBackAndStopsAtStart() {
        PlaybackQueue q = new PlaybackQueue(new Random(1));
        q.setSongs(songs(4));
        q.setMode(PlaybackQueue.Mode.RANDOM);
        int start = q.getIndex();
        q.next();
        int second = q.getIndex();
        assertEquals(start, q.previous());
        // 已在序列首位：保持当前歌曲
        assertEquals(start, q.previous());
        assertNotNull(second);
    }

    @Test
    public void replacedQueueResetsShuffleAndClampsIndex() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(5));
        q.moveTo(4);
        q.setMode(PlaybackQueue.Mode.RANDOM);
        q.setSongs(songs(2));
        assertEquals("越界下标必须被夹回有效范围", 1, q.getIndex());
        // 换列表后随机序列重建，不会引用旧下标
        q.setMode(PlaybackQueue.Mode.RANDOM);
        for (int i = 0; i < 10; i++) {
            int idx = q.next();
            assertTrue(idx >= 0 && idx < 2);
        }
    }

    @Test
    public void moveByStableId() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        assertTrue(q.moveToSongId(2));
        assertEquals(1, q.getIndex());
        assertFalse("找不到的 ID 不改变当前位置", q.moveToSongId(99));
        assertEquals(1, q.getIndex());
        assertFalse(q.moveTo(10));
        assertFalse(q.moveTo(-1));
    }

    @Test
    public void peekNextFollowsOrderModeWrap() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        assertEquals(1, q.peekNext());
        q.moveTo(2);
        assertEquals("末尾环绕到第一首", 0, q.peekNext());
    }

    @Test
    public void peekNextInSingleModeIsCurrentSong() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        q.moveTo(1);
        q.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(1, q.peekNext());
    }

    @Test
    public void peekNextInRandomModeIsStableAndDoesNotConsume() {
        PlaybackQueue q = new PlaybackQueue(new Random(7));
        q.setSongs(songs(6));
        q.setMode(PlaybackQueue.Mode.RANDOM);
        // 第一次 next 生成洗牌序列并落到序列首位
        int first = q.next();
        int peeked = q.peekNext();
        // 连续 peek 稳定
        assertEquals(peeked, q.peekNext());
        // peek 不消耗序列：下次 next 得到的正是 peek 的结果
        assertEquals(peeked, q.next());
    }

    @Test
    public void peekNextAtRandomRoundEndIsStay() {
        PlaybackQueue q = new PlaybackQueue(new Random(3));
        q.setSongs(songs(3));
        q.setMode(PlaybackQueue.Mode.RANDOM);
        // 洗牌序列以当前歌为首位：两次 next 后到达轮次末尾，
        // 下一轮需要重新洗牌，无法预测
        q.next();
        q.next();
        assertEquals(PlaybackQueue.STAY, q.peekNext());
    }

    @Test
    public void changeListenerFiresOnMutations() {
        PlaybackQueue q = new PlaybackQueue();
        int[] count = {0};
        q.addChangeListener(() -> count[0]++);
        q.setSongs(songs(3));
        assertEquals("setSongs", 1, count[0]);
        q.moveTo(1);
        assertEquals("moveTo", 2, count[0]);
        q.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals("setMode", 3, count[0]);
        assertEquals(PlaybackQueue.STAY, q.next()); // 单曲返回 STAY，无通知
        assertEquals("SINGLE next 不推进不通知", 3, count[0]);
        q.setMode(PlaybackQueue.Mode.ORDER);
        assertEquals("setMode ORDER", 4, count[0]);
        q.next();
        assertEquals("next 推进", 5, count[0]);
    }

    @Test
    public void changeListenerNotifiedWhenNextAdvances() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        int[] count = {0};
        q.addChangeListener(() -> count[0]++);
        q.setMode(PlaybackQueue.Mode.ORDER);
        int before = count[0];
        q.next();
        assertEquals(1, count[0] - before);
        q.previous();
        assertEquals(2, count[0] - before);
    }

    @Test
    public void listenerSurvivesNestedMutation() {
        // 监听器回调中再改队列（Service 切歌的真实场景），不得 ConcurrentModification
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(3));
        final int[] depth = {0};
        q.addChangeListener(() -> {
            if (depth[0] == 0) {
                depth[0]++;
                q.moveTo(1); // 嵌套变更
                depth[0]--;
            }
        });
        q.moveTo(2); // 应正常完成
        assertEquals(1, q.getIndex());
    }

    @Test
    public void emptyQueueIsSafe() {
        PlaybackQueue q = new PlaybackQueue();
        assertTrue(q.isEmpty());
        assertNull(q.current());
        assertEquals(PlaybackQueue.STAY, q.next());
        assertEquals(PlaybackQueue.STAY, q.previous());
        q.setMode(PlaybackQueue.Mode.RANDOM);
        assertNull(q.shuffleOrderSnapshot());
        assertFalse(q.moveTo(0));
    }

    @Test
    public void setSongsCopiesList() {
        PlaybackQueue q = new PlaybackQueue();
        List<Song> source = songs(2);
        q.setSongs(source);
        source.clear();
        assertEquals("队列不应受外部列表修改影响", 2, q.size());
    }

    @Test
    public void atReturnsNullOutOfRange() {
        PlaybackQueue q = new PlaybackQueue();
        q.setSongs(songs(2));
        assertNotNull(q.at(0));
        assertNull(q.at(2));
        assertNull(q.at(-1));
        assertEquals(Arrays.asList(1L, 2L),
                Arrays.asList(q.getSongs().get(0).getId(), q.getSongs().get(1).getId()));
    }
}
