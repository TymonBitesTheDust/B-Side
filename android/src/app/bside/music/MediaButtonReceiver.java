package app.bside.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Play on a car stereo, Bluetooth headset or speaker while B-Side isn't running. MusicService registers this
 * receiver with its media session, so Android remembers B-Side as the last music app and sends it the button even
 * after B-Side was closed. B-Side then starts its player without a screen and picks up the last song.
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev != null && (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0)) return;
        String cmd;
        switch (ev == null ? KeyEvent.KEYCODE_MEDIA_PLAY : ev.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                cmd = "play";
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                cmd = "next";
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                cmd = "prev";
                break;
            default:
                return; // pause or stop: nothing is playing anyway
        }
        MusicService.resume(context, cmd);
    }
}
