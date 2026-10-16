package local.jiege.hook.common;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 35mm-equivalent focal length of a zoom ratio, interpolated between the camera's zoom stops
 * (com.oplus.available.sat.all.zoomvalues, entries of the form "ratio(mm)").
 */
public final class ZoomFocal {
    private static final TreeMap<Float, Float> STOPS = new TreeMap<>();

    private ZoomFocal() {}

    public static float millimetres(Symbols symbols, float zoom) throws Throwable {
        synchronized (STOPS) {
            if (STOPS.isEmpty()) {
                @SuppressWarnings("unchecked") List<String> values = (List<String>) symbols.call(
                    "CameraConfig.list", null, "com.oplus.available.sat.all.zoomvalues");
                for (String value : values) {
                    int open = value.indexOf('(');
                    STOPS.put(Float.parseFloat(value.substring(0, open)),
                        Float.parseFloat(value.substring(open + 1, value.length() - 1)));
                }
            }
            Map.Entry<Float, Float> below = STOPS.floorEntry(zoom);
            Map.Entry<Float, Float> above = STOPS.ceilingEntry(zoom);
            if (below == null) return above.getValue() * zoom / above.getKey();
            if (above == null) return below.getValue() * zoom / below.getKey();
            if (below.getKey().equals(above.getKey())) return below.getValue();
            return below.getValue() + (above.getValue() - below.getValue())
                * (zoom - below.getKey()) / (above.getKey() - below.getKey());
        }
    }
}
