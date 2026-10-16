package local.jiege.hook.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery multi-select "更多" menu: 批量编辑水印, between 调整日期与时间 and 合成 GIF.
 *
 * The photos go one by one through the stock photo editor, opened on its 水印 menu with the menu
 * bar hidden, so every photo is rendered and saved by the gallery itself (the saved file keeps the
 * unwatermarked original, which is what lets 无 remove a watermark). The first photo is the
 * preview: the user picks there and 保存 starts the batch. What was chosen goes to the others: a style is
 * applied the way the editor reapplies a photo's own style when it opens (MasterVM.onImagePack);
 * 无 (the off item next to the series) is selected the same way the user did. Then the editor's
 * 保存 is pressed. Every editor after the first runs on a private virtual display the gallery owns,
 * so the user can leave for other apps: an editor on that display stays visible to the system,
 * which lets the chain start the next one from the background and keeps the process alive.
 * Progress is a notification.
 */
public final class BatchWatermark {
    private static final String TAG = "BatchWatermark";
    private static final String EDITOR = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static final String MENU_BAR = "com.oplus.gallery.standard_lib.ui.bottomnavigation.BottomFloatingMenuBar";
    /** The editor's 水印 menu entry and its menu bar. */
    private static final String WATERMARK_MENU = "strategy_watermark_master";
    private static final String MENU_BAR_VIEW = "main_menu_container";
    private static final String SECONDARY_PANEL = "secondary_menu_container";
    private static final String EXTRA_STEP = "jiege_batch_watermark_step";
    /** Editors whose 水印 menu was opened; a recreated editor (rotation) is a new instance and opens it again. */
    private static final java.util.Set<Activity> OPENED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Title id of the menu item; Resources.getText answers it with TITLE. */
    private static final int TITLE_ID = 0x7f3f0b01;
    private static final String TITLE = "批量编辑水印";
    private static final long SAVE_TIMEOUT_MS = 20000;
    /** The off item (无) in the watermark series row. */
    private static final String NONE = "picture3d_editor_id_watermark_master_none";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Uri> queue = new ArrayList<>();
    private static boolean running;
    /** The first photo's choice: a style id, or none for 无. */
    private static String styleId;
    /** The style and its operator as the first editor applied them (the operator keeps edited text). */
    private static Object chosenStyle;
    private static Object chosenOperator;
    private static boolean noneChosen;
    private static int saved;
    /** The batch editor on screen and whether its 保存 was pressed. */
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static boolean confirmed;

    private BatchWatermark() {}

    public static void installGallery(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "menu title", BatchWatermark::hookTitle);
        Log.guard(TAG, "more menu item", () -> hookMenu(classLoader));
        Log.guard(TAG, "batch editors", () -> hookEditor(classLoader));
        Log.guard(TAG, "style capture and reapply", () -> hookStyle(symbols));
    }

    // --- menu ---------------------------------------------------------------------------------

    private static void hookTitle() {
        XposedHelpers.findAndHookMethod(Resources.class, "getText", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if ((Integer) param.args[0] == TITLE_ID) param.setResult(TITLE);
            }
        });
    }

    /** BottomFloatingMenuBar.c(anchorId, fillItemList, onItemClick) opens the "更多" popup. */
    private static void hookMenu(ClassLoader classLoader) {
        Class<?> bar = XposedHelpers.findClass(MENU_BAR, classLoader);
        Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", classLoader);
        Class<?> function2 = XposedHelpers.findClass("kotlin.jvm.functions.Function2", classLoader);
        Object unit = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", classLoader), "INSTANCE");
        int hooked = 0;
        for (Method method : bar.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 3 || types[0] != int.class || types[1] != function1 || types[2] != function2) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    Object fill = param.args[1];
                    Object click = param.args[2];
                    param.args[1] = Proxy.newProxyInstance(classLoader, new Class<?>[] {function1}, (proxy, m, args) -> {
                        if (!"invoke".equals(m.getName())) return objectMethod(proxy, m, args);
                        Object result = XposedHelpers.callMethod(fill, "invoke", args[0]);
                        addItem(view.getContext(), (List<Object>) args[0]);
                        return result;
                    });
                    param.args[2] = click == null ? null : Proxy.newProxyInstance(classLoader, new Class<?>[] {function2}, (proxy, m, args) -> {
                        if (!"invoke".equals(m.getName())) return objectMethod(proxy, m, args);
                        if (titleOf(args[1]) != TITLE_ID) return XposedHelpers.callMethod(click, "invoke", args[0], args[1]);
                        choose(activity(view.getContext()), click);
                        return unit;
                    });
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new IllegalStateException("no popup method on " + MENU_BAR);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            default: return TAG;
        }
    }

    /** Only the multi-select "更多" list holds 调整日期与时间; the item goes right after it. */
    private static void addItem(Context context, List<Object> items) throws Throwable {
        int adjust = context.getResources().getIdentifier("base_adjust_sheet_title", "string", context.getPackageName());
        int at = -1;
        for (int i = 0; i < items.size(); i++) {
            int title = titleOf(items.get(i));
            if (title == TITLE_ID) return;
            if (title == adjust) at = i + 1;
        }
        if (at < 0) return;
        Constructor<?> item = items.get(0).getClass().getDeclaredConstructor(int.class, boolean.class, boolean.class, boolean.class);
        item.setAccessible(true);
        items.add(at, item.newInstance(TITLE_ID, false, true, true));
    }

    /** MenuListPopupItem(titleId, isRedDotVisible, isEnabled, dismissWithWindowAnimation): the int field. */
    private static int titleOf(Object item) {
        if (item == null) return 0;
        for (Field field : item.getClass().getDeclaredFields()) {
            if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    return field.getInt(item);
                } catch (IllegalAccessException error) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) context = ((ContextWrapper) context).getBaseContext();
        return context instanceof Activity ? (Activity) context : null;
    }

    private static void choose(Activity activity, Object click) {
        if (activity == null) return;
        if (running) {
            Toast.makeText(activity, "上一批水印还在处理", Toast.LENGTH_SHORT).show();
            return;
        }
        Object selection = selection(click);
        List<Uri> uris = selection == null ? new ArrayList<>() : imageUris(activity, XposedHelpers.callMethod(selection, "getSelectedItems"));
        if (uris.isEmpty()) {
            Toast.makeText(activity, "没有可编辑水印的照片", Toast.LENGTH_SHORT).show();
            return;
        }
        XposedHelpers.callMethod(selection, "exitSelectionMode");
        start(activity, uris);
    }

    /**
     * The selection (it has getSelectedItems) is held by what the click callback captured: the
     * fragment or BottomMenuHelper, possibly behind a WeakReference. Searched two levels deep.
     */
    private static Object selection(Object click) {
        Object found = findSelection(click, 0);
        if (found == null) {
            StringBuilder fields = new StringBuilder();
            for (Field field : click.getClass().getDeclaredFields()) fields.append(field.getType().getName()).append(' ');
            Log.i(TAG, "no selection in " + click.getClass().getName() + ": " + fields);
        }
        return found;
    }

    private static Object findSelection(Object holder, int depth) {
        if (holder == null || depth > 2) return null;
        if (holder instanceof WeakReference) return findSelection(((WeakReference<?>) holder).get(), depth);
        if (hasSelectedItems(holder)) return holder;
        if (holder.getClass().getName().startsWith("java.") || holder.getClass().getName().startsWith("android.")) return null;
        for (Class<?> cls = holder.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            if (cls.getName().startsWith("android.") || cls.getName().startsWith("androidx.")) break;
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType().isPrimitive() || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object found = findSelection(field.get(holder), depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean hasSelectedItems(Object target) {
        for (Method method : target.getClass().getMethods()) {
            if ("getSelectedItems".equals(method.getName()) && method.getParameterTypes().length == 0) return true;
        }
        return false;
    }

    /**
     * A selected path (/local/item/image/<gallery id>) holds its loaded MediaObject in a
     * WeakReference; MediaObject's only no-argument Uri method gives the content uri. Videos and
     * paths without a loaded item are skipped.
     */
    private static List<Uri> imageUris(Context context, Object selected) {
        List<Uri> uris = new ArrayList<>();
        if (!(selected instanceof Collection)) return uris;
        for (Object path : (Collection<?>) selected) {
            String text = String.valueOf(path);
            if (text.contains("video")) continue;
            Uri uri = contentUri(path);
            Log.i(TAG, "selected " + text + " -> " + uri);
            if (uri != null && readable(context, uri)) uris.add(uri);
        }
        return uris;
    }

    /** The gallery can list items whose file is already gone; the editor cannot open those. */
    private static boolean readable(Context context, Uri uri) {
        try (android.os.ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return file != null;
        } catch (Throwable error) {
            Log.i(TAG, "skipped, not readable: " + uri);
            return false;
        }
    }

    private static Uri contentUri(Object path) {
        for (Field field : path.getClass().getDeclaredFields()) {
            if (field.getType() != WeakReference.class) continue;
            try {
                field.setAccessible(true);
                Object item = ((WeakReference<?>) field.get(path)).get();
                if (item == null) continue;
                for (Class<?> cls = item.getClass(); cls != null; cls = cls.getSuperclass()) {
                    if (!cls.getName().endsWith(".MediaObject")) continue;
                    for (Method method : cls.getDeclaredMethods()) {
                        if (method.getReturnType() == Uri.class && method.getParameterTypes().length == 0) {
                            method.setAccessible(true);
                            return (Uri) method.invoke(item);
                        }
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "content uri of " + path + " failed", error);
            }
        }
        return null;
    }

    // --- the editor chain ---------------------------------------------------------------------

    private static void start(Activity from, List<Uri> uris) {
        queue.clear();
        queue.addAll(uris);
        running = true;
        styleId = null;
        chosenStyle = null;
        chosenOperator = null;
        noneChosen = false;
        saved = 0;
        open(from, 0);
    }

    private static boolean interactive(int step) {
        return step == 0;
    }

    private static void open(Activity from, int step) {
        Intent intent = new Intent(Intent.ACTION_EDIT)
            .setDataAndType(queue.get(step), "image/*")
            .setClassName(from.getPackageName(), EDITOR)
            .putExtra(EXTRA_STEP, step);
        if (interactive(step)) {
            from.startActivity(intent);
            return;
        }
        try {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display(from));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            from.startActivity(intent, options.toBundle());
        } catch (Throwable error) {
            Log.e(TAG, "editor did not open on the hidden display", error);
            running = false;
            releaseDisplay();
            post(from, notification(from).setContentText("批量编辑水印中断：" + saved + " / " + queue.size() + " 张已完成").setAutoCancel(true));
        }
    }

    /**
     * system_server: a virtual display owned by an app takes only activities declared embeddable,
     * and the photo editor is not. The gallery may put its own editor on its own batch display.
     */
    public static void installSystem(ClassLoader classLoader) {
        Log.guard(TAG, "editor on the batch display", () -> XposedHelpers.findAndHookMethod(
            "com.android.server.wm.ActivityTaskSupervisor", classLoader, "isCallerAllowedToLaunchOnDisplay",
            int.class, int.class, int.class, android.content.pm.ActivityInfo.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) return;
                    android.content.pm.ActivityInfo info = (android.content.pm.ActivityInfo) param.args[3];
                    int callingUid = (Integer) param.args[1];
                    if (info == null || !EDITOR.equals(info.name) || info.applicationInfo == null || info.applicationInfo.uid != callingUid) return;
                    Object displays = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.hardware.display.DisplayManagerGlobal", null), "getInstance");
                    Object display = XposedHelpers.callMethod(displays, "getDisplayInfo", param.args[2]);
                    if (display != null && DISPLAY_NAME.equals(XposedHelpers.getObjectField(display, "name"))
                            && XposedHelpers.getIntField(display, "ownerUid") == callingUid) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }));
    }

    // --- the hidden display and the progress notification ---------------------------------

    private static android.hardware.display.VirtualDisplay display;
    private static android.media.ImageReader frames;
    private static final String DISPLAY_NAME = "jiege-batch-watermark";
    private static final String CHANNEL = "jiege_batch_watermark";
    private static final int NOTIFICATION = 0x6a1e;
    /** The main blue of the gallery icon. */
    private static final int GALLERY_BLUE = 0xFF318CFA;

    /** A private display the size of the screen; its frames are read and dropped. */
    private static int display(Context context) {
        if (display == null) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            frames = android.media.ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2);
            android.os.HandlerThread thread = new android.os.HandlerThread("jiege-batch-frames");
            thread.start();
            frames.setOnImageAvailableListener(reader -> {
                android.media.Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            }, new Handler(thread.getLooper()));
            android.hardware.display.DisplayManager manager = context.getSystemService(android.hardware.display.DisplayManager.class);
            display = manager.createVirtualDisplay(DISPLAY_NAME, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                frames.getSurface(), android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        }
        return display.getDisplay().getDisplayId();
    }

    private static void releaseDisplay() {
        if (display != null) display.release();
        if (frames != null) frames.close();
        display = null;
        frames = null;
    }

    /**
     * A live update (ProgressStyle, promoted ongoing; the live card shows no other bar), posted as
     * each photo is done: the finished part in the gallery icon's blue, no tracker icon, and the status
     * bar chip count the finished photos.
     */
    private static void notifyProgress(Context context, int done) {
        android.graphics.Bitmap none = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        post(context, notification(context)
            .setStyle(new android.app.Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithBitmap(none))
                .setProgressSegments(segments(done))
                .setProgress(done))
            .setContentText("已完成 " + done + " 张，共 " + queue.size() + " 张")
            .setShortCriticalText(done + "/" + queue.size())
            .setRequestPromotedOngoing(true)
            .setOngoing(true));
    }

    /** The finished photos in the gallery blue, the rest in the system's default track colour. */
    private static List<android.app.Notification.ProgressStyle.Segment> segments(int done) {
        List<android.app.Notification.ProgressStyle.Segment> segments = new ArrayList<>();
        if (done > 0) segments.add(new android.app.Notification.ProgressStyle.Segment(done).setColor(GALLERY_BLUE));
        if (done < queue.size()) segments.add(new android.app.Notification.ProgressStyle.Segment(queue.size() - done));
        return segments;
    }

    private static void notifyDone(Context context) {
        int skipped = queue.size() - saved;
        post(context, notification(context)
            .setContentText(skipped == 0 ? "已完成 " + saved + " 张" : "已完成 " + saved + " 张，" + skipped + " 张未能保存")
            .setAutoCancel(true));
    }

    private static android.app.Notification.Builder notification(Context context) {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, TITLE, android.app.NotificationManager.IMPORTANCE_LOW));
        }
        return new android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(context.getApplicationInfo().icon)
            .setContentTitle(TITLE)
            .setColor(GALLERY_BLUE)
            .setOnlyAlertOnce(true);
    }

    private static void post(Context context, android.app.Notification.Builder builder) {
        try {
            context.getSystemService(android.app.NotificationManager.class).notify(NOTIFICATION, builder.build());
        } catch (Throwable error) {
            Log.e(TAG, "progress notification failed", error);
        }
    }

    private static int step(Activity activity) {
        Intent intent = activity.getIntent();
        return intent == null || !running ? -1 : intent.getIntExtra(EXTRA_STEP, -1);
    }

    private static void hookEditor(ClassLoader classLoader) {
        Class<?> editor = XposedHelpers.findClass(EDITOR, classLoader);
        XposedHelpers.findAndHookMethod(editor, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                int step = step(activity);
                if (step < 0) return;
                current = new WeakReference<>(activity);
                confirmed = false;
                View decor = activity.getWindow().getDecorView();
                decor.getViewTreeObserver().addOnPreDrawListener(() -> {
                    keepWatermarkOnly(activity);
                    return true;
                });
                if (interactive(step)) return;
                MAIN.postDelayed(() -> giveUp(activity), SAVE_TIMEOUT_MS);
            }
        });
        XposedHelpers.findAndHookMethod(editor, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (step(activity) < 0 || activity != current.get()) return;
                if (!OPENED.add(activity)) return;
                watermarkOnly(activity, 0);
            }
        });
        // 保存 and 无 may be pressed through their listeners without performClick; both paths are seen.
        XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                observe((View) param.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(View.class, "setOnClickListener", View.OnClickListener.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View.OnClickListener listener = (View.OnClickListener) param.args[0];
                View view = (View) param.thisObject;
                if (listener == null || listener instanceof Observed || !watched(view)) return;
                param.args[0] = new Observed(listener);
            }
        });
        // After saving, the editor opens the photo page; from the hidden display it would bring the
        // gallery to the front. Editors of the batch open only the next editor.
        XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult", Intent.class, int.class, android.os.Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                Intent target = (Intent) param.args[0];
                if (!editor.isInstance(activity) || activity.getIntent() == null || activity.getIntent().getIntExtra(EXTRA_STEP, 0) <= 0) return;
                if (target != null && target.getComponent() != null && EDITOR.equals(target.getComponent().getClassName())) return;
                Log.i(TAG, "batch editor kept from opening " + target);
                param.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (activity != current.get() || !editor.isInstance(activity)) return;
                current = new WeakReference<>(null);
                int step = step(activity);
                if (step < 0) return;
                if (interactive(step) && (!confirmed || (styleId == null && !noneChosen))) {
                    Log.i(TAG, "batch left at the first photo, style " + styleId);
                    running = false;
                    return;
                }
                if (confirmed) saved++;
                if (step + 1 < queue.size()) notifyProgress(activity, step + 1);
                next(activity, step + 1);
            }
        });
    }

    private static final class Observed implements View.OnClickListener {
        final View.OnClickListener listener;

        Observed(View.OnClickListener listener) {
            this.listener = listener;
        }

        @Override public void onClick(View view) {
            observe(view);
            listener.onClick(view);
        }
    }

    private static int confirmId;
    private static int noneId;

    private static boolean watched(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        if (confirmId == 0) {
            Resources resources = view.getResources();
            String pkg = view.getContext().getPackageName();
            confirmId = resources.getIdentifier("photoeditor_action_confirm", "id", pkg);
            noneId = resources.getIdentifier(NONE, "id", pkg);
        }
        return id == confirmId || id == noneId;
    }

    private static void observe(View view) {
        Activity activity = current.get();
        if (activity == null || view.getId() == View.NO_ID || activity(view.getContext()) != activity) return;
        if (view.getId() == id(activity, "photoeditor_action_confirm")) confirmed = true;
        if (view.getId() == id(activity, NONE) && interactive(step(activity))) {
            noneChosen = true;
            styleId = null;
        }
    }

    /** Editors whose watermark panel is open. */
    private static final java.util.Set<Activity> PANEL = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Before every frame: the menu bar stays hidden, and until the watermark panel is open the
     * secondary panel (the editor starts on 裁剪旋转) is hidden too, so neither is ever drawn.
     */
    private static void keepWatermarkOnly(Activity activity) {
        View bar = activity.findViewById(id(activity, MENU_BAR_VIEW));
        if (bar != null && bar.getVisibility() != View.INVISIBLE) bar.setVisibility(View.INVISIBLE);
        View panel = activity.findViewById(id(activity, SECONDARY_PANEL));
        int wanted = PANEL.contains(activity) ? View.VISIBLE : View.INVISIBLE;
        if (panel != null && panel.getVisibility() != wanted) panel.setVisibility(wanted);
    }

    /**
     * Opens the 水印 menu. The entry is hidden, so the tap goes to the view itself; it takes taps
     * only once the editor is ready, so it is tapped until the panel's 无 item is laid out.
     */
    private static void watermarkOnly(Activity activity, int attempt) {
        if (activity != current.get() || activity.isFinishing()) return;
        View none = activity.findViewById(id(activity, NONE));
        if (none != null && none.getWidth() > 0) {
            PANEL.add(activity);
            keepWatermarkOnly(activity);
            return;
        }
        if (attempt >= 100) {
            Log.i(TAG, "watermark panel did not open");
            PANEL.add(activity);
            keepWatermarkOnly(activity);
            return;
        }
        View menu = activity.findViewById(id(activity, WATERMARK_MENU));
        if (menu != null && menu.getWidth() > 0 && attempt % 3 == 0) tap(menu);
        MAIN.postDelayed(() -> watermarkOnly(activity, attempt + 1), 100);
    }

    /** A tap delivered to the view itself; a tap on a window of the hidden display has no input path. */
    private static void tap(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        float x = view.getWidth() / 2f;
        float y = view.getHeight() / 2f;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void next(Activity from, int step) {
        if (step < queue.size()) {
            open(from, step);
            return;
        }
        notifyDone(from.getApplicationContext());
        running = false;
        releaseDisplay();
    }

    /** An editor that did not reach 保存 in time is closed unsaved; the chain goes on. */
    private static void giveUp(Activity activity) {
        if (activity == current.get() && !activity.isFinishing()) {
            Log.i(TAG, "no save within " + SAVE_TIMEOUT_MS + " ms: " + activity.getIntent().getData());
            activity.finish();
        }
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    // --- style --------------------------------------------------------------------------------

    private static void hookStyle(Symbols symbols) {
        Method applyStyle = symbols.method("MasterVM.applyStyle");
        Method styleSource = symbols.method("StyleData.source");
        Class<?> loaderClass = symbols.method("StyleLoader.resolve").getDeclaringClass();
        XposedBridge.hookMethod(applyStyle, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = current.get();
                if (activity != null && interactive(step(activity))) {
                    styleId = (String) param.args[1];
                    chosenStyle = param.args[2];
                    chosenOperator = param.args[3];
                    noneChosen = false;
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("MasterVM.onImagePack"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = current.get();
                if (activity == null || !running) return;
                int step = step(activity);
                if (step < 0 || interactive(step)) return;
                Object vm = param.thisObject;
                try {
                    if (!noneChosen) apply(vm, activity, applyStyle, styleSource, loaderClass);
                    else clickNone(activity, System.currentTimeMillis());
                } catch (Throwable error) {
                    Log.e(TAG, "batch style failed", error);
                }
                MAIN.postDelayed(() -> pressSave(activity, System.currentTimeMillis()), 1200);
            }
        });
    }

    /** The editor's own path for a photo that already has a style: load it by id, then apply. */
    private static void apply(Object vm, Activity activity, Method applyStyle, Method styleSource, Class<?> loaderClass) throws Throwable {
        applyStyle.setAccessible(true);
        if (chosenStyle != null && chosenOperator != null) {
            applyStyle.invoke(null, vm, styleId, chosenStyle, chosenOperator, null, 16);
            return;
        }
        Object source = styleSource.invoke(null, styleId);
        Constructor<?> constructor = loaderClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object loader = constructor.newInstance(activity.getApplicationContext());
        Object pair = null;
        for (Method method : loaderClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 3 && source != null && types[0].isInstance(source) && types[1] == boolean.class && types[2] == boolean.class
                    && method.getReturnType().getName().equals("kotlin.Pair")) {
                method.setAccessible(true);
                pair = method.invoke(loader, source, false, true);
                break;
            }
        }
        if (pair == null) {
            StringBuilder methods = new StringBuilder();
            for (Method method : loaderClass.getDeclaredMethods()) methods.append(method).append("; ");
            throw new IllegalStateException("style " + styleId + " did not load from " + source + " (" + (source == null ? null : source.getClass()) + "), loader " + methods);
        }
        Object style = XposedHelpers.callMethod(pair, "getFirst");
        Object operator = XposedHelpers.callMethod(pair, "getSecond");
        applyStyle.setAccessible(true);
        applyStyle.invoke(null, vm, styleId, style, operator, null, 16);
    }

    /** 无 is pressed once the watermark panel has laid it out. */
    private static void clickNone(Activity activity, long since) {
        if (activity != current.get() || activity.isFinishing()) return;
        View none = activity.findViewById(id(activity, NONE));
        if (none != null && none.getWidth() > 0) {
            none.performClick();
            return;
        }
        if (System.currentTimeMillis() - since < SAVE_TIMEOUT_MS) MAIN.postDelayed(() -> clickNone(activity, since), 200);
        else Log.i(TAG, "no 无 item: " + activity.getIntent().getData());
    }

    /** 保存 becomes enabled once the editor holds a change; then it is pressed once. */
    private static void pressSave(Activity activity, long since) {
        if (activity != current.get() || activity.isFinishing() || confirmed) return;
        View save = activity.findViewById(id(activity, "photoeditor_action_confirm"));
        if (save != null && save.isEnabled() && save.isShown()) {
            save.performClick();
            return;
        }
        if (System.currentTimeMillis() - since < SAVE_TIMEOUT_MS) MAIN.postDelayed(() -> pressSave(activity, since), 300);
    }
}
