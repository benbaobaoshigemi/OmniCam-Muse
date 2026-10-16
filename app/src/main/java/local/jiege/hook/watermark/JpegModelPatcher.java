package local.jiege.hook.watermark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Rewrites the model name in JPEG bytes the camera has already encoded (its native code writes the
 * name, so Java cannot set it beforehand). Pure Java, no Android classes.
 *
 * The name is replaced in place and the data never changes size: the camera's photos are multi-picture
 * files (main picture, gain map, appended camera data) whose offset tables would all be wrong if the
 * leading Exif segment grew. Two places carry the name:
 *
 *   the IFD0 Model tag of the leading Exif APP1 segment: a name that fits the old value (with its
 *   terminating zero, or exactly without it) is written over it, the tag count follows; a longer one
 *   is left alone;
 *   the camera's appended data, where the name sits in a zero-padded field: anywhere in the file, a
 *   zero-terminated copy of the old name is replaced while the name fits in the old name plus the
 *   zero padding behind it.
 */
public final class JpegModelPatcher {
    private static final int TAG_MODEL = 0x0110;
    private static final int TYPE_ASCII = 2;

    private JpegModelPatcher() {}

    /** What {@link #patch} did. */
    public static final class Result {
        public boolean exif;
        public int appended;
        public boolean tooLong;
        public String original;

        public boolean changed() {
            return exif || appended > 0;
        }
    }

    /** The Model tag of the leading Exif segment of {@code data[base..]}, or null. */
    public static String readModel(byte[] data, int base) {
        Tiff tiff = Tiff.find(data, base);
        if (tiff == null) return null;
        int entry = tiff.modelEntry();
        if (entry < 0) return null;
        int count = tiff.buffer.getInt(entry + 4);
        int valueAt = count <= 4 ? entry + 8 : tiff.start + tiff.buffer.getInt(entry + 8);
        if (count < 1 || valueAt < 0 || valueAt + count > data.length) return null;
        int end = valueAt;
        while (end < valueAt + count && data[end] != 0) end++;
        return new String(data, valueAt, end - valueAt, StandardCharsets.UTF_8);
    }

    /**
     * Replaces the model in {@code data[base..base+length)} in place. {@code fallbackOriginal} is the
     * name to look for when the region has no readable Model tag (a chunk of a file that does not start
     * at the JPEG header); it may be null.
     */
    public static Result patch(byte[] data, int base, int length, String fallbackOriginal, String name) {
        Result result = new Result();
        byte[] value = name.getBytes(StandardCharsets.UTF_8);
        String found = readModel(data, base);
        String original = found != null && !found.isEmpty() ? found : fallbackOriginal;
        result.original = original;
        if (original == null || original.isEmpty() || original.equals(name)) return result;
        if (found != null && !found.isEmpty()) {
            switch (patchModelTag(data, base, value)) {
                case 1: result.exif = true; break;
                case -1: result.tooLong = true; break;
                default: break;
            }
        }
        result.appended = patchAppended(data, base, length, original.getBytes(StandardCharsets.UTF_8), value);
        return result;
    }

    /** 1 replaced, 0 nothing to do, -1 the name does not fit. */
    private static int patchModelTag(byte[] data, int base, byte[] value) {
        Tiff tiff = Tiff.find(data, base);
        if (tiff == null) return 0;
        int entry = tiff.modelEntry();
        if (entry < 0) return 0;
        ByteBuffer b = tiff.buffer;
        int oldCount = b.getInt(entry + 4);
        int valueAt = tiff.start + b.getInt(entry + 8);
        if (oldCount <= 4 || valueAt < 0 || valueAt + oldCount > data.length) return 0;
        // The terminating zero goes first; a name that only fits without it is written without it.
        int newCount = value.length + 1 <= oldCount ? value.length + 1 : value.length <= oldCount ? value.length : -1;
        if (newCount < 0) return -1;
        Arrays.fill(data, valueAt, valueAt + oldCount, (byte) 0);
        System.arraycopy(value, 0, data, valueAt, value.length);
        b.putInt(entry + 4, newCount);
        return 1;
    }

    /** Zero-terminated copies of the old name in the region, replaced in place; returns how many. */
    private static int patchAppended(byte[] data, int base, int length, byte[] old, byte[] value) {
        int end = Math.min(data.length, base + length);
        int replaced = 0;
        for (int at = base; at + old.length < end; at++) {
            if (data[at] != old[0] || data[at + old.length] != 0 || !regionEquals(data, at, old)) continue;
            int zeros = 0;
            while (at + old.length + zeros < end && data[at + old.length + zeros] == 0) zeros++;
            if (value.length + 1 > old.length + zeros) continue;
            Arrays.fill(data, at, at + old.length, (byte) 0);
            System.arraycopy(value, 0, data, at, value.length);
            replaced++;
            at += old.length;
        }
        return replaced;
    }

    private static boolean regionEquals(byte[] data, int at, byte[] what) {
        for (int i = 1; i < what.length; i++) {
            if (data[at + i] != what[i]) return false;
        }
        return true;
    }

    /** The leading Exif APP1 segment as a TIFF block. */
    private static final class Tiff {
        final ByteBuffer buffer;
        final int start;
        final int length;

        private Tiff(ByteBuffer buffer, int start, int length) {
            this.buffer = buffer;
            this.start = start;
            this.length = length;
        }

        static Tiff find(byte[] data, int base) {
            if (base < 0 || data.length < base + 20) return null;
            if ((data[base] & 0xFF) != 0xFF || (data[base + 1] & 0xFF) != 0xD8 || (data[base + 2] & 0xFF) != 0xFF || (data[base + 3] & 0xFF) != 0xE1) return null;
            int segmentLength = ((data[base + 4] & 0xFF) << 8) | (data[base + 5] & 0xFF);
            if (data[base + 6] != 'E' || data[base + 7] != 'x' || data[base + 8] != 'i' || data[base + 9] != 'f' || data[base + 10] != 0 || data[base + 11] != 0) return null;
            int start = base + 12;
            int length = segmentLength - 8;
            if (length < 8 || start + length > data.length) return null;
            ByteOrder order = data[start] == 'M' && data[start + 1] == 'M' ? ByteOrder.BIG_ENDIAN
                : data[start] == 'I' && data[start + 1] == 'I' ? ByteOrder.LITTLE_ENDIAN : null;
            if (order == null) return null;
            return new Tiff(ByteBuffer.wrap(data).order(order), start, length);
        }

        /** Absolute position of the IFD0 Model entry (ASCII), or -1. */
        int modelEntry() {
            int ifd = start + buffer.getInt(start + 4);
            if (ifd < start || ifd + 2 > start + length) return -1;
            int entries = buffer.getShort(ifd) & 0xFFFF;
            for (int i = 0; i < entries; i++) {
                int entry = ifd + 2 + 12 * i;
                if (entry + 12 > start + length) return -1;
                if ((buffer.getShort(entry) & 0xFFFF) == TAG_MODEL && (buffer.getShort(entry + 2) & 0xFFFF) == TYPE_ASCII) return entry;
            }
            return -1;
        }
    }
}
