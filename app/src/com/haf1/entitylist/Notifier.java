package com.haf1.entitylist;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;

/**
 * 新记录提示音。
 *
 * 优先用系统「通知」提示音（用户自己选的铃声，走通知音量，静音模式下自然不出声）；
 * 若系统没设默认通知音，退回 ToneGenerator 发一声短促蜂鸣。
 * 全部只用 framework 类，不需要任何音频资源文件。
 */
public class Notifier {

    private final Context ctx;
    private Ringtone ringtone;
    private ToneGenerator tone;

    public Notifier(Context ctx) {
        this.ctx = ctx;
    }

    /** 响一声。多次调用会先打断上一次，避免叠成一片噪音。 */
    public void beep() {
        if (playSystemSound()) {
            return;
        }
        playTone();
    }

    private boolean playSystemSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) {
                return false;
            }
            if (ringtone == null) {
                ringtone = RingtoneManager.getRingtone(ctx, uri);
            }
            if (ringtone == null) {
                return false;
            }
            if (ringtone.isPlaying()) {
                ringtone.stop();
            }
            ringtone.play();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void playTone() {
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250);
        } catch (Throwable ignored) {
            // 连蜂鸣都放不出来就算了，不能因为提示音把主流程搞崩
        }
    }

    /** Activity 销毁时调用，释放底层音频资源。 */
    public void release() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        ringtone = null;
        try {
            if (tone != null) {
                tone.release();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        tone = null;
    }
}
