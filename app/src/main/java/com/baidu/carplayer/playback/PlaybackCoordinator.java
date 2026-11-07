package com.baidu.carplayer.playback;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 播放命令的唯一串行入口和状态发布器。
 * Android Service 可以把 ExoPlayer 操作提交到这里，页面只订阅快照。
 */
public final class PlaybackCoordinator implements AutoCloseable {
    public interface StateSubscriber {
        void onStateChanged(PlaybackState state);
    }

    private final ExecutorService executor;
    private final Set<StateSubscriber> subscribers = new CopyOnWriteArraySet<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile PlaybackState state;

    public PlaybackCoordinator(PlaybackState initialState) {
        this.state = initialState;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "playback-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    public PlaybackState getState() {
        return state;
    }

    public void subscribe(StateSubscriber subscriber) {
        if (subscriber == null || closed.get()) return;
        subscribers.add(subscriber);
        subscriber.onStateChanged(state);
    }

    public void unsubscribe(StateSubscriber subscriber) {
        if (subscriber != null) subscribers.remove(subscriber);
    }

    /** 所有命令均在同一个线程执行，避免队列和播放器状态交错修改。 */
    public void dispatch(PlaybackCommand command, Function<PlaybackState, PlaybackState> stateUpdater) {
        if (command == null || stateUpdater == null || closed.get()) return;
        executor.execute(() -> {
            if (closed.get()) return;
            PlaybackState newState = stateUpdater.apply(state);
            if (newState != null) {
                state = newState;
                for (StateSubscriber subscriber : subscribers) {
                    subscriber.onStateChanged(newState);
                }
            }
        });
    }

    /** 在协调器线程内发布新的不可变快照。 */
    public void publish(PlaybackState newState) {
        if (newState == null || closed.get()) return;
        executor.execute(() -> {
            if (closed.get()) return;
            state = newState;
            for (StateSubscriber subscriber : subscribers) {
                subscriber.onStateChanged(newState);
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscribers.clear();
            executor.shutdownNow();
        }
    }
}
