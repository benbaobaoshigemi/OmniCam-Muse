package local.jiege.hook.gr;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * The GR palette: the same dial the master page uses, wired to whichever two GR parameters are
 * selected, with the axis readout and the parameters as horizontal rows above it. The camera keeps
 * its own style strip over the preview - that is where a style is chosen - and the block reads it
 * only for the dial's colour.
 *
 * <p>The dial reports master-scale tone and saturation values; each axis is mapped onto the step
 * range of the parameter it currently drives, so the same knob covers any of the eleven.
 */
public final class GrPanel {
    private static final String TAG = "GrPanel";
    private static final String PANEL_VIEW =
        "com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView";
    private static final String LISTENER = PANEL_VIEW + "$OnToneSaturationChangeListener";
    /** The dial speaks master values; the GR rows are stepped, and step 4 is the centre. */
    private static final int DIAL_FULL = 100;
    private static final int STEP_ZERO = 4;
    /** The parameter strip above the dial. */
    private static final int CHIP_ROW_DP = 33;
    /** Font sizes tried for the chips, largest first: the strip must stay at two lines to fit above the shutter. */
    private static final int[] CHIP_SP = {10, 9, 8};
    private static final int MAX_ROWS = 2;
    /**
     * Which of the two block layouts is drawn. The strip (one line of chips above the dial, docked
     * under the shutter) is the one in use; the ring (labels round the dial, centred on the picture)
     * is kept, but it covers the preview, so it is off until it is wanted again.
     */
    private static final boolean RING = false;
    /**
     * Clearance kept on the right. The strip is docked below the shutter row on the GR page, so it may
     * use the full width; a reserve here only stole chips per line (it forced a third line).
     */
    private static final int CHIP_RIGHT_RESERVE_DP = 0;

    /** The camera's own styles, read off its strip and switched by clicking it. */
    public interface Styles {
        List<String> labels();
        /** Index of the style the camera currently has, or -1 when it cannot be told. */
        int current();
        void select(int index);
        /** The dial's colour recipe for a style, or null when the camera has none for it. */
        Object recipe(int index);
    }

    private static List<GrEffectValues.Spec> specs = new ArrayList<>();
    private static Styles styles;
    private static int styleIndex = -1;
    private static String xParam;
    private static String yParam;
    private static boolean armingY;
    private static ViewGroup paramStrip;
    private static Object dial;

    private GrPanel() {}

    public static boolean ready() {
        return !GrEffectValues.specs().isEmpty();
    }

    /**
     * Loads the parameter list without building anything. The caller needs it before it can ask for
     * the ring's size - {@link #side} measures the chips, and an empty list would under-report the
     * radius and clip the ring to a box too small for it.
     */
    public static void prepare() {
        specs = GrEffectValues.specs();
    }

    /** How tall the block is: the strip plus the dial, or the ring's square. */
    public static int height(Activity activity, int dialPx) {
        return RING ? side(activity, dialPx) : dp(activity, CHIP_ROW_DP) + dialPx;
    }

    /** True when the ring layout is selected (see {@link #RING}). */
    public static boolean ringEnabled() {
        return RING;
    }

    /**
     * The chip font that keeps the strip inside {@link #MAX_ROWS} lines. The GR panel offers eleven
     * parameters and they do not fit one line, while a third line pushes the block over the shutter -
     * so the font steps down until two lines hold everything.
     */
    private static int stripSp(Activity activity) {
        for (int sp : CHIP_SP) {
            if (rowCount(activity, sp) <= MAX_ROWS) return sp;
        }
        return CHIP_SP[CHIP_SP.length - 1];
    }

    /** How many lines the chips take at this display width and font size. */
    private static int rowCount(Activity activity, int sp) {
        if (specs.isEmpty()) return 1;
        int available = availableWidth(activity);
        int used = 0;
        int count = 1;
        for (GrEffectValues.Spec spec : specs) {
            int width = chipWidth(activity, spec.label, sp);
            if (used > 0 && used + width > available) {
                count++;
                used = 0;
            }
            used += width;
        }
        return count;
    }

    /** The width the chips may use: the screen, minus the block's side margins and the style pill. */
    private static int availableWidth(Activity activity) {
        return activity.getResources().getDisplayMetrics().widthPixels
            - dp(activity, 16) - dp(activity, CHIP_RIGHT_RESERVE_DP);
    }

    /** A chip's own width plus the margin its layout params add, at the given font size. */
    private static int chipWidth(Activity activity, String label, int sp) {
        TextView probe = chip(activity, label, sp);
        probe.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return probe.getMeasuredWidth() + dp(activity, 5);
    }

    /**
     * Builds the palette block: the parameters as a horizontal strip with the dial centred under it.
     * Neither the styles nor a text readout are part of it - the styles are chosen on the camera's
     * own strip over the preview (read here only for the dial's colour), and which parameter sits on
     * which axis is told by the chips' own colours.
     */
    public static View build(Activity activity, int dialPx, Styles source) {
        specs = GrEffectValues.specs();
        if (specs.isEmpty()) return null;
        styles = source;
        styleIndex = styles == null ? -1 : styles.current();
        if (xParam == null || !has(xParam)) xParam = pick("camera_gr_tone");
        if (yParam == null || !has(yParam) || yParam.equals(xParam)) {
            yParam = pick("camera_gr_saturation");
        }

        // Two layouts exist for the block. The strip (chips on one line, dial under it) is the one in
        // use; the ring (labels round the dial, centred on the preview) is kept behind this flag
        // because it covers the picture and was put aside. Flip it to true to get the ring back.
        dialogSetup(activity);
        View root = RING ? ring(activity, dialPx) : strip(activity, dialPx);
        if (root == null) return null;

        highlight();
        StringBuilder names = new StringBuilder();
        for (GrEffectValues.Spec spec : specs) names.append(' ').append(spec.name);
        Log.i(TAG, "built " + specs.size() + " parameters, "
            + (styles == null ? 0 : styles.labels().size()) + " styles, axes "
            + xParam + "/" + yParam + ", style " + (styleIndex < 0 ? "?" : "" + styleIndex)
            + ", dial " + dialPx + ", layout " + (RING ? "ring" : "strip") + ", params" + names);
        return root;
    }

    /** The dial, its colour recipe and its listener; both layouts share it. */
    private static void dialogSetup(Activity activity) {
        dial = XposedHelpers.newInstance(
            XposedHelpers.findClass(PANEL_VIEW, activity.getClassLoader()), activity);
        applyRecipe(styles == null ? null : styles.recipe(styleIndex));
        XposedHelpers.callMethod(dial, "setCurrentToneAndSaturation", dialOf(xParam), dialOf(yParam));
        XposedHelpers.callMethod(dial, "setOnToneSaturationChangeListener",
            Proxy.newProxyInstance(activity.getClassLoader(), new Class<?>[] { listener(activity) },
                (proxy, method, args) -> {
                    if ("onToneSaturationChanged".equals(method.getName())) {
                        moved(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "AuraGrPalette";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                }));
    }

    /** The original layout: the parameters on one line above the dial, the block docked under the shutter. */
    private static View strip(Activity activity, int dialPx) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout params = new LinearLayout(activity);
        params.setOrientation(LinearLayout.HORIZONTAL);
        for (GrEffectValues.Spec spec : specs) {
            TextView chip = chip(activity, spec.label, CHIP_SP[0]);
            chip.setTag(spec.name);
            chip.setOnClickListener(v -> select(spec));
            params.addView(chip);
        }
        paramStrip = params;
        root.addView(scroller(activity, params), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, CHIP_ROW_DP)));
        LinearLayout.LayoutParams dialParams = new LinearLayout.LayoutParams(dialPx, dialPx);
        dialParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView((View) dial, dialParams);
        return root;
    }

    /** The ring layout: labels round the dial, block centred on the preview (covers the picture). */
    private static View ring(Activity activity, int dialPx) {
        int radius = ringRadius(activity, dialPx);
        int side = side(activity, dialPx);
        FrameLayout box = new FrameLayout(activity);
        int count = specs.size();
        for (int i = 0; i < count; i++) {
            GrEffectValues.Spec spec = specs.get(i);
            TextView chip = chip(activity, shortLabel(spec.label), CHIP_SP[0]);
            chip.setTag(spec.name);
            chip.setOnClickListener(v -> select(spec));
            FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.gravity = Gravity.CENTER;
            box.addView(chip, chipParams);
            final double angle = -Math.PI / 2 + 2 * Math.PI * i / count;
            chip.post(() -> {
                chip.setTranslationX((float) (radius * Math.cos(angle)));
                chip.setTranslationY((float) (radius * Math.sin(angle)));
            });
        }
        paramStrip = box;
        FrameLayout.LayoutParams dialParams = new FrameLayout.LayoutParams(dialPx, dialPx);
        dialParams.gravity = Gravity.CENTER;
        box.addView((View) dial, dialParams);
        box.setLayoutParams(new FrameLayout.LayoutParams(side, side));
        Log.i(TAG, "ring r=" + radius + " side=" + side);
        return box;
    }

    /** A chip was tapped: it becomes the X axis, the next tap the Y axis, as before. */
    private static void select(GrEffectValues.Spec spec) {
        if (armingY) {
            if (!spec.name.equals(xParam)) yParam = spec.name;
            armingY = false;
        } else {
            xParam = spec.name;
            if (spec.name.equals(yParam)) yParam = other(spec.name);
            armingY = true;
        }
        setDial();
        highlight();
    }

    /** Side of the square the ring needs: the circle plus room for the chips hanging off it. */
    public static int side(Activity activity, int dialPx) {
        return 2 * ringRadius(activity, dialPx) + dp(activity, CHIP_ROW_DP);
    }

    /**
     * Radius that keeps eleven shortened labels from overlapping while staying clear of the dial:
     * the one the labels need along the circle, or the one the dial needs, whichever is larger.
     */
    private static int ringRadius(Activity activity, int dialPx) {
        int gap = dp(activity, 10);
        int reach = 0;
        int circumference = 0;
        for (GrEffectValues.Spec spec : specs) {
            int width = chipWidth(activity, shortLabel(spec.label), CHIP_SP[0]);
            reach = Math.max(reach, width);
            circumference += width + gap;
        }
        int byLabels = Math.round(circumference / (float) (2 * Math.PI));
        int byDial = dialPx / 2 + dp(activity, 16) + dp(activity, 12);
        return Math.max(byLabels, byDial);
    }

    /**
     * The label as it fits on the ring. The camera names the contrast flavours with a suffix
     * ("对比度（亮部）"), which is the part that tells them apart - so the suffix is what is kept.
     */
    static String shortLabel(String label) {
        if (label == null || label.isEmpty()) return "";
        String text = label.replace('（', '(').replace('）', ')');
        int open = text.indexOf('(');
        if (open > 0) {
            String head = text.substring(0, open);
            String tail = text.substring(open + 1).replace(")", "");
            if (head.startsWith("对比度")) return tail;
            if (head.startsWith("颗粒")) return "强度".equals(tail) ? "颗粒" : "粒径";
        }
        if (text.startsWith("饱和度")) return "饱和";
        if (text.startsWith("对比度")) return "对比";
        return text;
    }

    public static void release() {
        dial = null;
        paramStrip = null;
        styles = null;
        styleIndex = -1;
    }

    private static View scroller(Activity activity, View content) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    /** One line of chips, sized like the single row used to be. */
    private static LinearLayout newRow(Activity activity) {
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, CHIP_ROW_DP)));
        return chipRow;
    }

    private static TextView chip(Activity activity, String text, int sp) {
        TextView chip = new TextView(activity);
        chip.setText(text);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setTextColor(0xFFEDEDED);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(activity, 7), dp(activity, 3), dp(activity, 7), dp(activity, 3));
        GradientDrawable background = new GradientDrawable();
        // Opaque enough to read over a bright preview: the ring floats on the picture, so a bare
        // outline disappeared into it.
        background.setColor(0xB3000000);
        background.setCornerRadius(dp(activity, 10));
        background.setStroke(Math.max(1, dp(activity, 1)), 0x66FFFFFF);
        chip.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(activity, 5);
        params.topMargin = dp(activity, 3);
        params.bottomMargin = dp(activity, 4);
        chip.setLayoutParams(params);
        return chip;
    }

    private static void applyRecipe(Object recipe) {
        if (dial == null) return;
        try {
            if (recipe == null) {
                Log.i(TAG, "no colour recipe for style " + styleIndex + ": the dial stays flat");
                return;
            }
            XposedHelpers.callMethod(dial, "setColorRecipe", recipe);
        } catch (Throwable error) {
            Log.i(TAG, "colour recipe skipped " + error);
        }
    }

    private static void setDial() {
        if (dial == null) return;
        try {
            XposedHelpers.callMethod(dial, "setCurrentToneAndSaturation",
                dialOf(xParam), dialOf(yParam));
        } catch (Throwable error) {
            Log.i(TAG, "dial sync skipped " + error);
        }
    }

    /** The dial's own value for a parameter's current step. */
    private static int dialOf(String parameter) {
        int step = GrEffectValues.step(parameter);
        if (step == Integer.MIN_VALUE) step = STEP_ZERO;
        return (step - STEP_ZERO) * DIAL_FULL / STEP_ZERO;
    }

    private static void moved(int x, int y) {
        GrEffectValues.set(xParam, stepOf(x));
        GrEffectValues.set(yParam, stepOf(y));
    }

    private static int stepOf(int dialValue) {
        int step = Math.round(dialValue * (float) STEP_ZERO / DIAL_FULL) + STEP_ZERO;
        return Math.max(0, Math.min(STEP_ZERO * 2, step));
    }

    private static void highlight() {
        if (paramStrip == null) return;
        // The strip is one group per line now, so every line is walked.
        for (int i = 0; i < paramStrip.getChildCount(); i++) {
            View child = paramStrip.getChildAt(i);
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) child;
                for (int j = 0; j < group.getChildCount(); j++) highlightChip(group.getChildAt(j));
            } else {
                highlightChip(child);
            }
        }
    }

    private static void highlightChip(View chip) {
        if (chip.getTag() == null) return;
        String name = String.valueOf(chip.getTag());
        boolean x = name.equals(xParam);
        boolean y = name.equals(yParam);
        // The chips carry a dark plate so they stay readable over the picture; the axes are told
        // apart by tint instead of opacity, which over a bright scene made them disappear.
        chip.setAlpha(x || y ? 1f : 0.85f);
        tint(chip, x ? 0xFF8A5A00 : y ? 0xFF14506E : 0xB3000000);
    }

    private static void tint(View chip, int colour) {
        if (chip.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) chip.getBackground()).setColor(colour);
        }
    }

    private static boolean has(String name) {
        for (GrEffectValues.Spec spec : specs) if (spec.name.equals(name)) return true;
        return false;
    }

    private static String pick(String preferred) {
        if (has(preferred)) return preferred;
        return specs.isEmpty() ? preferred : specs.get(0).name;
    }

    private static String other(String name) {
        for (GrEffectValues.Spec spec : specs) if (!spec.name.equals(name)) return spec.name;
        return name;
    }

    private static Class<?> listener(Activity activity) {
        return XposedHelpers.findClass(LISTENER, activity.getClassLoader());
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
