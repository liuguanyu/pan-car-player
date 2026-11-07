package com.baidu.carplayer.playback;

import android.view.KeyEvent;

import com.baidu.carplayer.service.AudioPlayerService;

/** 统一的前台媒体按键映射；所有按键都转为 PlaybackCommand 交给 Service 命令入口。 */
public final class MediaKeyDispatcher {
    private MediaKeyDispatcher() {
    }

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

    public static boolean dispatch(KeyEvent event, AudioPlayerService service, Runnable afterTrackChange) {
        if (event == null) {
            return false;
        }
        PlaybackCommand.Type type = toCommandType(event.getKeyCode());
        if (type == null) {
            return false;
        }
        // 消费 ACTION_UP，避免同一按键被系统 MediaSession 再处理一次。
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
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
}
