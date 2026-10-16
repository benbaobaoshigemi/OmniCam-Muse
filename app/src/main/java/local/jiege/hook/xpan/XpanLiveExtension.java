package local.jiege.hook.xpan;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Adds the moov/lpex box the camera writes into its live photo clips: "LivePhotoExtension"
 * followed by JSON, filled like the camera's clips: videoSize and cropRect are the photo area inside
 * the clip (which also shows a frame watermark) and originPhotoSize and photoCropRect the clean
 * photo. Sizes are landscape, the way the clip is stored (videoOrientation turns it upright). The
 * clip is unstabilized and uncropped, so the factors are 1 and the matrices identity.
 */
final class XpanLiveExtension {
    private static final int MOOV = 0x6d6f6f76;
    private static final int FREE = 0x66726565;
    private static final int LPEX = 0x6c706578;

    private XpanLiveExtension() {}

    static void write(File video, XpanDevelopingVideo.Clip clip) throws IOException {
        byte[] box = box(json(clip));
        byte[] data = Files.readAllBytes(video.toPath());
        ByteBuffer atoms = ByteBuffer.wrap(data);
        int moov = -1;
        int after = -1;
        for (int pos = 0; pos < data.length; ) {
            long size = Integer.toUnsignedLong(atoms.getInt(pos));
            if (size == 1) size = atoms.getLong(pos + 8);  // 64-bit size follows the type
            else if (size == 0) size = data.length - pos;  // box runs to the end of the file
            if (size < 8 || size > data.length - pos) throw new IOException("bad MP4 box at " + pos + ", size " + size);
            if (moov >= 0 && after < 0) after = pos;
            if (atoms.getInt(pos + 4) == MOOV) moov = pos;
            pos += (int) size;
        }
        if (moov < 0) throw new IOException("MP4 without moov");
        int moovSize = atoms.getInt(moov);
        int moovEnd = moov + moovSize;
        byte[] result;
        if (after < 0) {
            // moov is last: grow it, nothing after it moves.
            result = new byte[data.length + box.length];
            System.arraycopy(data, 0, result, 0, data.length);
            System.arraycopy(box, 0, result, data.length, box.length);
        } else if (atoms.getInt(after + 4) == FREE && atoms.getInt(after) - box.length >= 8) {
            // moov is followed by padding: take the box out of it, nothing else moves.
            result = data.clone();
            int freeSize = atoms.getInt(after);
            System.arraycopy(box, 0, result, moovEnd, box.length);
            ByteBuffer.wrap(result).putInt(moovEnd + box.length, freeSize - box.length).putInt(moovEnd + box.length + 4, FREE);
        } else {
            throw new IOException("moov is neither last nor followed by free space");
        }
        ByteBuffer.wrap(result).putInt(moov, moovSize + box.length);
        Files.write(video.toPath(), result);
    }

    private static byte[] box(String json) {
        byte[] payload = ("LivePhotoExtension" + json).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length).putInt(LPEX).put(payload).array();
    }

    private static String json(XpanDevelopingVideo.Clip clip) throws IOException {
        int width = clip.areaWidth;
        int height = clip.areaHeight;
        int originWidth = clip.photoWidth;
        int originHeight = clip.photoHeight;
        try {
            return new JSONObject()
                .put("coverFramePts", clip.coverUs)
                .put("cropRect", new JSONArray().put(0).put(0).put(width).put(height))
                .put("desc", "OppoMotionVideoExt")
                .put("eisCropFactor", new JSONArray().put(1.0).put(1.0))
                .put("matrixCount", 0)
                .put("originPhotoSize", new JSONArray().put(originWidth).put(originHeight))
                .put("photoCropFactor", 1.0)
                .put("photoCropMatrix", identity())
                .put("photoCropRect", new JSONArray().put(0).put(0).put(originWidth).put(originHeight))
                .put("photoEisCropFactor", new JSONArray().put(1.0).put(1.0))
                .put("photoEisMatrix", identity())
                .put("subVideoScaleFactor", 1.0)
                .put("version", 1)
                .put("videoOrientation", clip.rotation)
                .put("videoSize", new JSONArray().put(width).put(height))
                .toString();
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    private static JSONArray identity() throws JSONException {
        return new JSONArray().put(1.0).put(0.0).put(0.0).put(0.0).put(1.0).put(0.0).put(0.0).put(0.0).put(1.0);
    }
}
