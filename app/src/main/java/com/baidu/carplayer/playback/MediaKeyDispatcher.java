package com.baidu.carplayer.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.baidu.carplayer.service.AudioPlayerService;

/**
 * 统一的前台媒体按键映射；所有按键都转为 PlaybackCommand 交给 Service 命令入口。
 *
 * <p>「下一首 / 上一首」支持长按快进 / 快退：按下后由 {@link SeekHold} 判定是否越过长按时长，
 * 越过则转为连续跳转，松开时不再换曲。短按行为不变。
 *
 * <p>每个 Activity 持有一个实例，页面不可见时调用 {@link #cancel()} 停掉计时器。
 */
public final class MediaKeyDispatcher {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SeekHold hold = new SeekHold();
    private AudioPlayerService holdingService;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            AudioPlayerService service = holdingService;
            if (service == null || !hold.isActive()) return;
            long delta = hold.tick(SystemClock.uptimeMillis());
            if (delta != 0L) service.seekBy(delta);
            if (hold.isActive()) handler.postDelayed(this, SeekHold.TICK_MS);
        }
    };

    public static PlaybackCommand.Type toCommandType(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return PlaybackCommand.Type.TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return PlaybackCommand.Type.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return PlaybackCommand.Type.PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return PlaybackCommand.Type.NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return PlaybackCommand.Type.PREVIOUS;
            default:
                return null;
        }
    }

    public boolean dispatch(KeyEvent event, AudioPlayerService service, Runnable afterTrackChange) {
        if (event == null) {
            return false;
        }
        KeyTrace.log(event);
        PlaybackCommand.Type type = toCommandType(event.getKeyCode());
        if (type == null) {
            return false;
        }
        int direction = SeekHold.directionFor(type);
        if (direction != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) beginHold(direction, service);
                // 按住期间的重复事件不重复派发，由计时器驱动跳转。
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && endHold()) {
                // 已作为长按快进消费，不再换曲。
                return true;
            }
        } else if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            // 消费 ACTION_UP，避免同一按键被系统 MediaSession 再处理一次。
            return true;
        }
        if (service != null) {
            service.dispatch(type);
            if (afterTrackChange != null
                    && (type == PlaybackCommand.Type.NEXT || type == PlaybackCommand.Type.PREVIOUS)) {
                afterTrackChange.run();
            }
        }
        return true;
    }

    /** 页面不可见时释放长按状态，避免后台继续跳转。 */
    public void cancel() {
        handler.removeCallbacks(ticker);
        holdingService = null;
        hold.cancel();
    }

    private void beginHold(int direction, AudioPlayerService service) {
        handler.removeCallbacks(ticker);
        hold.begin(direction, SystemClock.uptimeMillis());
        holdingService = service;
        handler.postDelayed(ticker, SeekHold.TICK_MS);
    }

    private boolean endHold() {
        boolean consumed = hold.end();
        handler.removeCallbacks(ticker);
        holdingService = null;
        return consumed;
    }
}
