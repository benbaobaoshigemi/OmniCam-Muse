package local.jiege.hook.gallery;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import local.jiege.hook.common.Log;

/**
 * Gallery photo details: tapping the address (tv_address) puts the photo's position on a map. The
 * coordinates come from the photo's EXIF, found through the file name shown above it
 * (tv_full_name). Each map app gets its own marker link with the coordinates declared WGS-84 (as
 * EXIF stores them; the apps convert to their own datum), never a search. The gallery cannot see
 * which map apps are installed (package visibility), so all links go to the system chooser, which
 * drops the missing apps and opens a single remaining one directly. A photo without coordinates
 * opens nothing.
 */
public final class AddressNavigation {
    private static final String TAG = "AddressNavigation";

    /** Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE (hidden in the SDK). */
    private static final String AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE";

    private static int addressId;

    private AddressNavigation() {}

    public static void installGallery(ClassLoader classLoader) {
        Log.guard(TAG, "address opens the map", () -> XposedHelpers.findAndHookMethod(TextView.class, "setText",
            CharSequence.class, TextView.BufferType.class, boolean.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    TextView view = (TextView) param.thisObject;
                    if (view.getId() == View.NO_ID || view.isClickable()) return;
                    if (addressId == 0) addressId = id(view, "tv_address");
                    if (view.getId() != addressId) return;
                    view.setClickable(true);
                    view.setOnClickListener(AddressNavigation::open);
                }
            }));
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
    }

    private static void open(View view) {
        String address = ((TextView) view).getText().toString();
        String name = fileName(view);
        Context context = view.getContext();
        new Thread(() -> {
            double[] location = name == null ? null : location(context, name);
            if (location == null) {
                Log.i(TAG, "no coordinates for " + name);
                return;
            }
            List<Intent> maps = mapIntents(location[0], location[1], address);
            Intent intent = Intent.createChooser(maps.remove(0), "打开地图")
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, maps.toArray(new Intent[0]))
                .putExtra(AUTO_LAUNCH_SINGLE_CHOICE, true);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException error) {
                    Log.e(TAG, "map app did not open", error);
                }
            });
        }, TAG).start();
    }

    /** Marker links for the map apps, coordinates in WGS-84. */
    private static List<Intent> mapIntents(double latitude, double longitude, String title) {
        String lat = String.format(Locale.ROOT, "%.7f", latitude);
        String lon = String.format(Locale.ROOT, "%.7f", longitude);
        String name = Uri.encode(title);
        String[][] apps = {
            {"com.autonavi.minimap", "androidamap://viewMap?sourceApplication=OmniCam&poiname=" + name + "&lat=" + lat + "&lon=" + lon + "&dev=1"},
            {"com.baidu.BaiduMap", "baidumap://map/marker?location=" + lat + "," + lon + "&title=" + name + "&content=" + name + "&coord_type=wgs84&src=local.jiege.camera"},
            {"com.tencent.map", "qqmap://map/marker?marker=coord:" + lat + "," + lon + ";title:" + name + "&coord_type=1&referer=OmniCam"},
        };
        List<Intent> intents = new ArrayList<>();
        for (String[] app : apps) {
            intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse(app[1])).setPackage(app[0]));
        }
        return intents;
    }

    /** The file name the details panel shows next to the address. */
    private static String fileName(View address) {
        int nameId = id(address, "tv_full_name");
        for (Object parent = address.getParent(); parent instanceof ViewGroup; parent = ((ViewGroup) parent).getParent()) {
            View name = ((ViewGroup) parent).findViewById(nameId);
            if (name instanceof TextView) return ((TextView) name).getText().toString();
        }
        return null;
    }

    /** Latitude and longitude from the EXIF of the media file with this name, null without. */
    private static double[] location(Context context, String name) {
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor cursor = context.getContentResolver().query(files, new String[] {MediaStore.MediaColumns._ID},
                // The panel shows the name in upper case.
                MediaStore.MediaColumns.DISPLAY_NAME + " = ? COLLATE NOCASE", new String[] {name}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                Uri item = MediaStore.setRequireOriginal(Uri.withAppendedPath(files, String.valueOf(cursor.getLong(0))));
                try (InputStream in = context.getContentResolver().openInputStream(item)) {
                    if (in == null) continue;
                    float[] latLong = new float[2];
                    if (new ExifInterface(in).getLatLong(latLong)) return new double[] {latLong[0], latLong[1]};
                } catch (Exception error) {
                    Log.e(TAG, "EXIF of " + name + " unreadable", error);
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "media lookup of " + name + " failed", error);
        }
        return null;
    }
}
