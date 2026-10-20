package local.jiege.hook.common;

import android.view.Surface;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collection;

/**
 * Diagnostic probe (log only, no behaviour change).
 *
 * Entering POP makes the app re-configure its capture session and the HAL rejects it:
 *
 *   AidlCamera3-Device: configureStreams: Transaction error: ILLEGAL_ARGUMENT
 *   Camera3-Device: Camera 0: configureStreamsLocked: Set of requested inputs/outputs not supported
 *   CameraDevice-JV-0: Stream configuration failed due to: endConfigureLocked:946: Camera 0: ...
 *
 * The preview then never produces another frame, and because the camera device is shared, every mode
 * is frozen afterwards. Nothing in logcat names the streams (the request dies before cameraserver
 * creates them), so this logs:
 *
 *   1. every createCaptureSession() the app makes — output count, sessionType (OPPO vendor values),
 *      dynamic-range profile of each output, and the vendor session-parameter keys;
 *   2. every ImageReader / SurfaceTexture buffer the app allocates — the exact width x height and
 *      format that the streams are built from;
 *   3. a short caller stack.
 *
 * Compare a request from a working mode (7-12 outputs, sessionType 0x8010 / 0x8009 / 0x8001 / 0x8021
 * were observed) against the one POP makes. Every hook body is wrapped: a failure degrades to one log
 * line and never reaches the camera.
 */
public final class CameraSessionProbe {
    private static final String TAG = "PopPort";
    private static final String IMPL = "android.hardware.camera2.impl.CameraDeviceImpl";
    private static final String[] ENTRY_POINTS = {
        "createCaptureSession",
        "createCaptureSessionByOutputConfigurations",
    };
    private static final int STACK_FRAMES = 9;
    private static final int MAX_SESSION_KEYS = 30;

    private static volatile boolean installed;

    private CameraSessionProbe() {}

    public static synchronized void install(ClassLoader classLoader) {
        if (installed) return;
        int hooked = hookSessions(classLoader) + hookBuffers(classLoader);
        if (hooked > 0) {
            installed = true;
            Log.i(TAG, "session probe installed (" + hooked + " hook targets)");
        }
    }

    // ---------------------------------------------------------------- capture sessions

    private static int hookSessions(ClassLoader classLoader) {
        Class<?> impl;
        try {
            impl = Class.forName(IMPL, false, classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "session probe: " + IMPL + " unavailable", error);
            return 0;
        }
        int hooked = 0;
        for (String name : ENTRY_POINTS) {
            try {
                XposedBridge.hookAllMethods(impl, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Log.i(TAG, "session request: " + describe(param.args) + caller());
                        } catch (Throwable error) {
                            Log.e(TAG, "session probe failed", error);
                        }
                    }
                });
                hooked++;
            } catch (Throwable error) {
                Log.e(TAG, "session probe: cannot hook " + name, error);
            }
        }
        return hooked;
    }

    /** Renders the argument list of one createCaptureSession() overload. */
    private static String describe(Object[] args) {
        StringBuilder out = new StringBuilder();
        for (Object arg : args) {
            if (out.length() > 0) out.append(" | ");
            if (arg == null) {
                out.append("null");
            } else if (arg instanceof Surface) {
                out.append("output=").append(arg);
            } else {
                String configuration = renderConfiguration(arg);
                if (configuration != null) {
                    out.append(configuration);
                } else if (arg instanceof Collection) {
                    out.append(renderSurfaces((Collection<?>) arg));
                } else {
                    out.append(arg.getClass().getSimpleName());
                }
            }
        }
        return out.toString();
    }

    /** SessionConfiguration: outputs (with input), or null when the object is not one. */
    private static String renderConfiguration(Object configuration) {
        Object outputs = call(configuration, "getOutputConfigurations");
        if (!(outputs instanceof Collection)) return null;
        Collection<?> items = (Collection<?>) outputs;
        StringBuilder out = new StringBuilder("outputs(").append(items.size()).append(")[");
        boolean first = true;
        for (Object item : items) {
            if (!first) out.append(", ");
            first = false;
            out.append(renderOutput(item));
        }
        out.append(']');
        Object input = call(configuration, "getInputConfiguration");
        if (input != null) out.append(" input=").append(renderOutput(input));
        Object type = call(configuration, "getSessionType");
        if (type != null) out.append(" sessionType=").append(type);
        String keys = renderSessionKeys(configuration);
        if (keys != null) out.append(" sessionKeys=").append(keys);
        return out.toString();
    }

    /** Vendor keys carried by the session parameters (they usually name the mode/usecase). */
    private static String renderSessionKeys(Object configuration) {
        Object parameters = call(configuration, "getSessionParameters");
        if (parameters == null) return null;
        Object keys = call(parameters, "getKeys");
        if (!(keys instanceof Collection)) return null;
        StringBuilder out = new StringBuilder();
        int written = 0;
        for (Object key : (Collection<?>) keys) {
            if (key == null) continue;
            String name = key.toString();
            if (name.startsWith("android.")) continue;
            if (written++ == MAX_SESSION_KEYS) {
                out.append(", …");
                break;
            }
            if (out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String renderSurfaces(Collection<?> items) {
        StringBuilder out = new StringBuilder("outputs(").append(items.size()).append(")[");
        boolean first = true;
        for (Object item : items) {
            if (!first) out.append(", ");
            first = false;
            out.append(item instanceof Surface ? String.valueOf(item) : name(item));
        }
        return out.append(']').toString();
    }

    /** One OutputConfiguration: surface, usage flags, dynamic range profile, stream use case. */
    private static String renderOutput(Object configuration) {
        if (configuration == null) return "null";
        Object surface = call(configuration, "getSurface");
        StringBuilder out = new StringBuilder(surface == null ? name(configuration) : String.valueOf(surface));
        Object usage = call(configuration, "getUsageFlags");
        if (usage != null) out.append(" usage=").append(usage);
        Object dynamicRange = call(configuration, "getDynamicRangeProfile");
        if (dynamicRange != null) out.append(" dr=").append(dynamicRange);
        return out.toString();
    }

    // ---------------------------------------------------------------- buffers the streams are built from

    private static int hookBuffers(ClassLoader classLoader) {
        int hooked = 0;
        hooked += hookArguments(classLoader, "android.media.ImageReader", "newInstance", "ImageReader.newInstance");
        hooked += hookArguments(classLoader, "android.media.ImageReader", "<init>", "ImageReader.<init>");
        hooked += hookArguments(classLoader, "android.graphics.SurfaceTexture", "setDefaultBufferSize",
            "SurfaceTexture.setDefaultBufferSize");
        hooked += hookArguments(classLoader, "android.media.ImageWriter", "newInstance", "ImageWriter.newInstance");
        return hooked;
    }

    /** Logs the arguments of every overload of one framework method (sizes and formats). */
    private static int hookArguments(ClassLoader classLoader, String className, String methodName, String label) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "buffer probe: " + className + " unavailable", error);
            return 0;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    StringBuilder out = new StringBuilder(label);
                    for (Object arg : param.args) out.append(' ').append(render(arg));
                    Log.i(TAG, out.toString());
                } catch (Throwable error) {
                    Log.e(TAG, "buffer probe failed", error);
                }
            }
        };
        try {
            if ("<init>".equals(methodName)) {
                XposedBridge.hookAllConstructors(clazz, hook);
            } else {
                XposedBridge.hookAllMethods(clazz, methodName, hook);
            }
            return 1;
        } catch (Throwable error) {
            Log.e(TAG, "buffer probe: cannot hook " + className + "." + methodName, error);
            return 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof Integer || value instanceof Long || value instanceof Short
            || value instanceof Byte || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Surface) return String.valueOf(value);
        return value.getClass().getSimpleName();
    }

    /** The camera-mode code path that asked for this session, innermost first. */
    private static String caller() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder out = new StringBuilder(" from ");
        int written = 0;
        for (int i = 1; i < frames.length && written < STACK_FRAMES; i++) {
            String where = frames[i].getClassName();
            if (where.startsWith("de.robv.android.xposed") || where.startsWith("java.lang.reflect")
                || where.equals(CameraSessionProbe.class.getName())) {
                continue;
            }
            out.append(written == 0 ? "" : " <- ").append(shorten(where))
                .append('.').append(frames[i].getMethodName());
            written++;
        }
        return out.toString();
    }

    private static String shorten(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static Object call(Object target, String method) {
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String name(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
