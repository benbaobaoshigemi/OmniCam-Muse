package local.jiege.hook.xpan;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the camera's own XPAN shutter sound (raw/camera_xpan_shutter_sound, Ogg) at the start of
 * the clip. MP4 live photos carry AAC, so the sound is decoded and re-encoded.
 */
final class XpanShutterAudio {
    private static final String SOUND = "camera_xpan_shutter_sound";
    private static final int AAC_BIT_RATE = 128_000;

    private XpanShutterAudio() {}

    static void mux(Context camera, File silentVideo, File output, int rotation) throws IOException {
        Encoded audio = encodeShutter(camera);
        MediaExtractor video = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            video.setDataSource(silentVideo.getAbsolutePath());
            int videoSource = track(video, "video/");
            video.selectTrack(videoSource);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            int videoTarget = muxer.addTrack(video.getTrackFormat(videoSource));
            int audioTarget = muxer.addTrack(audio.format);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = 0;
            while (video.getSampleTrackIndex() >= 0) {
                long videoUs = video.getSampleTime();
                for (; next < audio.samples.size() && audio.times.get(next) <= videoUs; next++) {
                    writeAudio(muxer, audioTarget, audio, next, info);
                }
                buffer.clear();
                int size = video.readSampleData(buffer, 0);
                if (size < 0) break;
                int flags = (video.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, videoUs, flags);
                muxer.writeSampleData(videoTarget, buffer, info);
                video.advance();
            }
            for (; next < audio.samples.size(); next++) writeAudio(muxer, audioTarget, audio, next, info);
        } finally {
            video.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } finally {
                    muxer.release();
                }
            }
        }
    }

    private static void writeAudio(MediaMuxer muxer, int target, Encoded audio, int index, MediaCodec.BufferInfo info) {
        byte[] data = audio.samples.get(index);
        info.set(0, data.length, audio.times.get(index), 0);
        muxer.writeSampleData(target, ByteBuffer.wrap(data), info);
    }

    private static final class Encoded {
        MediaFormat format;
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
    }

    private static Encoded encodeShutter(Context camera) throws IOException {
        int id = camera.getResources().getIdentifier(SOUND, "raw", camera.getPackageName());
        if (id == 0) throw new IOException("camera has no raw/" + SOUND);
        int[] shape = new int[2];
        byte[] pcm;
        try (AssetFileDescriptor sound = camera.getResources().openRawResourceFd(id)) {
            if (sound == null) throw new IOException("raw/" + SOUND + " is compressed in the APK");
            pcm = decode(sound, shape);
        }
        return encode(pcm, shape[0], shape[1]);
    }

    /** Decodes to 16-bit PCM; shape receives {sampleRate, channels}. */
    private static byte[] decode(AssetFileDescriptor sound, int[] shape) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(sound.getFileDescriptor(), sound.getStartOffset(), sound.getLength());
            int source = track(extractor, "audio/");
            extractor.selectTrack(source);
            MediaFormat format = extractor.getTrackFormat(source);
            shape[0] = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            shape[1] = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("shutter sound decoder stalled");
                if (!inputDone) {
                    int in = decoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        int size = extractor.readSampleData(decoder.getInputBuffer(in), 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = decoder.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decoded = decoder.getOutputFormat();
                    shape[0] = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    shape[1] = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer data = decoder.getOutputBuffer(out);
                    byte[] chunk = new byte[info.size];
                    data.position(info.offset);
                    data.get(chunk);
                    pcm.write(chunk, 0, chunk.length);
                    decoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            return pcm.toByteArray();
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (IllegalStateException ignored) { }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static Encoded encode(byte[] pcm, int sampleRate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        Encoded result = new Encoded();
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frameBytes = 2 * channels;
            int offset = 0;
            boolean inputDone = false;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("AAC encoder stalled");
                if (!inputDone) {
                    int in = encoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(in);
                        int size = Math.min(buffer.remaining() / frameBytes * frameBytes, pcm.length - offset);
                        long timeUs = (offset / frameBytes) * 1_000_000L / sampleRate;
                        if (size <= 0) {
                            encoder.queueInputBuffer(in, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.put(pcm, offset, size);
                            encoder.queueInputBuffer(in, 0, size, timeUs, 0);
                            offset += size;
                        }
                    }
                }
                int out = encoder.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    result.format = encoder.getOutputFormat();
                } else if (out >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer data = encoder.getOutputBuffer(out);
                        byte[] sample = new byte[info.size];
                        data.position(info.offset);
                        data.get(sample);
                        result.samples.add(sample);
                        result.times.add(Math.max(0L, info.presentationTimeUs));
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            try { encoder.stop(); } catch (IllegalStateException ignored) { }
            encoder.release();
        }
        if (result.format == null || result.samples.isEmpty()) throw new IOException("AAC encoder produced nothing");
        return result;
    }

    private static int track(MediaExtractor extractor, String prefix) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix)) return i;
        }
        throw new IOException("no " + prefix + " track");
    }
}
