package local.jiege.hook.shutter;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * System framework (system_server) half of the shutter sound: turns on Android's "camera sound
 * forced" state, the one regions with a mandatory shutter sound use.
 *
 * AudioService.readCameraSoundForced() is read at start-up and on configuration changes. When it
 * returns true, AudioService takes STREAM_SYSTEM_ENFORCED (7) out of the ringer-mode muted streams
 * and pins its volume to the maximum. ShutterSound plays on that stream in silent/vibrate mode, so
 * the shutter is audible there at the module's own volume.
 *
 * Only sounds on STREAM_SYSTEM_ENFORCED are affected; on this device only ShutterSound uses it (the
 * camera's own enforced pool needs com.oplus.camera.force_play_shutter_sound, which is off).
 */
public final class CameraSoundForced {
    private static final String TAG = "CameraSoundForced";

    private CameraSoundForced() {}

    public static void installSystem(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.audio.AudioService", classLoader,
                "readCameraSoundForced", XC_MethodReplacement.returnConstant(true));
            Log.i(TAG, "installed");
        } catch (Throwable error) {
            Log.e(TAG, "camera sound forced hook unavailable", error);
        }
    }
}
