package local.jiege.hook.xpan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.view.Surface;
import android.view.animation.PathInterpolator;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Encodes the clip: the saved photo's layout at the camera's live clip scale (long side
 * {@value #MAX_LONG_SIDE}, as the gallery editor expects), with the clean photo in the photo area playing the camera's XPAN capture animation
 * (CoverDrawable: the inverted photo drawn over the photo, fading out over 2.5 s on
 * PathInterpolator(0.8, 0, 0.15, 0.65)) and the saved photo's pixels, a frame watermark, around it.
 * Stored landscape and rotated for display like the camera's live clips. Silent; the sound is added
 * by {@link XpanShutterAudio}.
 */
final class XpanDevelopingVideo {
    private static final int FPS = 30;
    /** 2.5 s, the duration of the camera's capture animation. */
    private static final int REVEAL_FRAMES = 75;
    private static final int HOLD_FRAMES = 9;
    /** Long side of the camera's own XPAN live clips (2160 x 748 stored). */
    private static final int MAX_LONG_SIDE = 2160;
    /** Bits per pixel per frame, about the camera's own live clips. */
    private static final float BITS_PER_PIXEL = 0.4f;
    private static final int MAX_BIT_RATE = 80_000_000;
    private static final String[] MIMES = {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC};
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final float[] QUAD = {
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f,
    };
    private static final String VERTEX =
        "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vStored;"
            + "void main(){gl_Position=vec4(aPosition,0.0,1.0);vStored=aTexCoord;}";
    // vStored is the stored (landscape) frame; uv the upright one, the stored frame turned 90 degrees
    // clockwise. In the photo area (uArea, upright) the clean photo with its inverse over it at alpha
    // uNegative (CoverDrawable); elsewhere the saved photo.
    private static final String FRAGMENT =
        "precision highp float; varying vec2 vStored;"
            + "uniform sampler2D uStill; uniform sampler2D uPhoto; uniform vec4 uArea; uniform float uNegative;"
            + "void main(){"
            + " vec2 uv=vec2(1.0-vStored.y,vStored.x);"
            + " vec2 p=(uv-uArea.xy)/(uArea.zw-uArea.xy);"
            + " if(p.x<0.0||p.y<0.0||p.x>1.0||p.y>1.0){gl_FragColor=vec4(texture2D(uStill,uv).rgb,1.0);return;}"
            + " vec3 photo=texture2D(uPhoto,p).rgb;"
            + " gl_FragColor=vec4(mix(photo,vec3(1.0)-photo,uNegative),1.0);"
            + "}";

    /**
     * The encoded clip: stored size, display rotation, codec, cover time, the photo area's size in
     * the stored clip and the clean photo's size (both landscape), and the photo area in the upright
     * clip's pixels.
     */
    static final class Clip {
        final int width;
        final int height;
        final int rotation;
        final String mime;
        final long coverUs;
        final int areaWidth;
        final int areaHeight;
        final int photoWidth;
        final int photoHeight;
        final Rect display;

        Clip(int width, int height, int rotation, String mime, long coverUs, int areaWidth, int areaHeight,
             int photoWidth, int photoHeight, Rect display) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.mime = mime;
            this.coverUs = coverUs;
            this.areaWidth = areaWidth;
            this.areaHeight = areaHeight;
            this.photoWidth = photoWidth;
            this.photoHeight = photoHeight;
            this.display = display;
        }
    }

    private XpanDevelopingVideo() {}

    /** Display rotation of the JPEG in degrees, from its EXIF orientation. */
    static int orientation(File jpeg) throws IOException {
        switch (new ExifInterface(jpeg.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    /**
     * Writes the clip of a saved photo (JPEG, savedWidth x savedHeight) whose photo area (its pixels)
     * holds the clean photo (JPEG) to output.
     */
    static Clip encode(File saved, int savedWidth, int savedHeight, byte[] photoJpeg, Rect area, int orientation,
                       File output) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(photoJpeg, 0, photoJpeg.length, bounds);
        // Stored landscape: the upright photo's height is the stored width.
        float cap = Math.min(1f, MAX_LONG_SIDE / (float) Math.max(savedWidth, savedHeight));
        int[] size = null;
        String mime = null;
        for (String candidate : MIMES) {
            size = encoderSize(candidate, Math.round(savedHeight * cap), Math.round(savedWidth * cap));
            if (size != null) {
                mime = candidate;
                break;
            }
        }
        if (size == null) throw new IOException("no encoder for " + savedHeight + "x" + savedWidth);
        int width = size[0];
        int height = size[1];
        int rotation = (orientation + 90) % 360;
        // Upright canvas height x width; the photo area scaled to it.
        float scale = height / (float) savedWidth;
        int areaWidth = Math.round(area.width() * scale);
        int areaHeight = Math.round(area.height() * scale);
        Bitmap still = decode(BitmapFactory.decodeFile(saved.getAbsolutePath(), sampled(savedWidth, savedHeight, height, width)),
            height, width);
        Bitmap photo = decode(BitmapFactory.decodeByteArray(photoJpeg, 0, photoJpeg.length,
            sampled(bounds.outWidth, bounds.outHeight, areaWidth, areaHeight)), areaWidth, areaHeight);
        float[] uv = {area.left / (float) savedWidth, area.top / (float) savedHeight,
            area.right / (float) savedWidth, area.bottom / (float) savedHeight};

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        Surface input = null;
        Renderer renderer = null;
        boolean muxerStarted = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(MAX_BIT_RATE, (long) width * height * FPS * BITS_PER_PIXEL));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            renderer = new Renderer(input, still, photo, uv, width, height);
            still.recycle();
            photo.recycle();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            codec.start();
            PathInterpolator curve = new PathInterpolator(0.8f, 0f, 0.15f, 0.65f);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            int frames = REVEAL_FRAMES + HOLD_FRAMES + 1;
            for (int frame = 0; frame < frames; frame++) {
                float progress = Math.min(1f, frame / (float) REVEAL_FRAMES);
                renderer.draw(1f - curve.getInterpolation(progress), frame * 1_000_000_000L / FPS);
                int[] state = drain(codec, muxer, info, track, muxerStarted, false);
                track = state[0];
                muxerStarted = state[1] != 0;
            }
            codec.signalEndOfInputStream();
            int[] state = drain(codec, muxer, info, track, muxerStarted, true);
            muxerStarted = state[1] != 0;
            return new Clip(width, height, rotation, mime, (frames - 1) * 1_000_000L / FPS, areaHeight, areaWidth,
                bounds.outHeight, bounds.outWidth, new Rect(Math.round(area.left * scale),
                Math.round(area.top * width / (float) savedHeight), Math.round(area.left * scale) + areaWidth,
                Math.round(area.top * width / (float) savedHeight) + areaHeight));
        } catch (RuntimeException error) {
            throw new IOException("XPAN clip encoding failed (" + mime + " " + width + "x" + height + ")", error);
        } finally {
            if (!still.isRecycled()) still.recycle();
            if (!photo.isRecycled()) photo.recycle();
            if (renderer != null) renderer.close();
            if (input != null) input.release();
            if (codec != null) {
                try { codec.stop(); } catch (IllegalStateException ignored) { }
                codec.release();
            }
            if (muxer != null) {
                if (muxerStarted) muxer.stop();
                muxer.release();
            }
        }
    }

    /**
     * The largest size (multiples of 16, same aspect ratio) not above width x height that an encoder
     * of the type takes, or null if none takes a useful size.
     */
    private static int[] encoderSize(String mime, int width, int height) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) continue;
                MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                for (float scale = 1f; scale > 0.2f; scale *= 0.9f) {
                    int w = Math.max(16, (int) (width * scale) / 16 * 16);
                    int h = Math.max(16, (int) (height * scale) / 16 * 16);
                    if (video.isSizeSupported(w, h)) return new int[]{w, h};
                }
            }
        }
        return null;
    }

    /** Decode options sampling a width x height JPEG down as far as it stays at least targetWidth x targetHeight. */
    private static BitmapFactory.Options sampled(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = sample;
        return options;
    }

    private static Bitmap decode(Bitmap decoded, int width, int height) throws IOException {
        if (decoded == null) throw new IOException("cannot decode a photo of the clip");
        if (decoded.getWidth() == width && decoded.getHeight() == height) return decoded;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        decoded.recycle();
        return scaled;
    }

    /** Moves encoded output into the muxer; returns {track, started}. With finish, waits for end of stream. */
    private static int[] drain(MediaCodec codec, MediaMuxer muxer, MediaCodec.BufferInfo info, int track,
                               boolean started, boolean finish) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, finish ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!finish) return new int[]{track, started ? 1 : 0};
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("XPAN encoder did not finish");
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (started) throw new IOException("XPAN encoder changed format twice");
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                started = true;
                continue;
            }
            if (index < 0) continue;
            boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                if (!started) throw new IOException("XPAN encoder output before format");
                ByteBuffer data = codec.getOutputBuffer(index);
                data.position(info.offset);
                data.limit(info.offset + info.size);
                muxer.writeSampleData(track, data, info);
            }
            codec.releaseOutputBuffer(index, false);
            if (ended) return new int[]{track, started ? 1 : 0};
        }
    }

    private static final class Renderer {
        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;
        private final int program;
        private final int[] textures = new int[2];
        private final int width;
        private final int height;
        private final FloatBuffer vertices;

        Renderer(Surface input, Bitmap still, Bitmap photo, float[] area, int width, int height) throws IOException {
            this.width = width;
            this.height = height;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) {
                throw new IOException("no EGL display");
            }
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw new IOException("no recordable EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IOException("no EGL recording surface");
            }
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IOException("shader link: " + GLES20.glGetProgramInfoLog(program));
            int[] maxTexture = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0);
            if (Math.max(still.getWidth(), still.getHeight()) > maxTexture[0]) {
                throw new IOException("photo " + still.getWidth() + "x" + still.getHeight() + " above GL texture limit " + maxTexture[0]);
            }
            GLES20.glUseProgram(program);
            GLES20.glGenTextures(2, textures, 0);
            Bitmap[] images = {still, photo};
            String[] names = {"uStill", "uPhoto"};
            for (int i = 0; i < 2; i++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, images[i], 0);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, names[i]), i);
            }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uArea"), area[0], area[1], area[2], area[3]);
            vertices = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(QUAD).position(0);
        }

        void draw(float negative, long timestampNs) throws IOException {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uNegative"), negative);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IOException("GL draw failed");
            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs);
            if (!EGL14.eglSwapBuffers(display, surface)) throw new IOException("EGL swap failed");
        }

        void close() {
            GLES20.glDeleteTextures(2, textures, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
        }

        private static int shader(int type, String source) throws IOException {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) throw new IOException("shader: " + GLES20.glGetShaderInfoLog(shader));
            return shader;
        }
    }
}
