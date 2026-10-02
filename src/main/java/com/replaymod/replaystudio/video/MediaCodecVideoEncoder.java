/*
 * Copyright (c) 2026
 *
 * This file is part of ReplayStudio.
 *
 * ReplayStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ReplayStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ReplayStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.replaymod.replaystudio.video;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * {@link VideoEncoder} backed by Android's {@code MediaCodec} and {@code MediaMuxer}.
 *
 * <p>This is the backend that actually solves rendering a replay with nothing installed on Android,
 * because the encoder is part of the OS: it is hardware accelerated, needs no {@code ffmpeg} binary and
 * no bundled native libraries, and produces colour-accurate H.264 plus AAC support if audio is added
 * later.</p>
 *
 * <p><b>Everything Android-specific is reached by reflection.</b> ReplayStudio is a plain Java library
 * with no Android dependency, and pulling {@code android.jar} onto its compile classpath would break
 * desktop consumers and the Java 8 toolchain. The Android classes are therefore resolved lazily, so
 * this class still loads and can be listed on a desktop JVM; it simply refuses to
 * {@link #open(VideoEncoderConfig)} there.</p>
 *
 * <p><b>Colour is handled by this library, not by the platform.</b> Frames arrive as RGB and are
 * converted to planar I420 by {@link Yuv420Converter}, whose output has been verified to match FFmpeg
 * exactly. This is deliberately done here: it is the step the pure-Java JCodec path got wrong.</p>
 *
 * <p><b>Known device caveats.</b> Hardware encoders vary. Some require the width and height to be a
 * multiple of 16 rather than merely even, and some expect semi-planar NV12 rather than planar I420.
 * {@link #open(VideoEncoderConfig)} surfaces the platform's own exception in that case rather than
 * swallowing it, so the caller can see what the device objected to.</p>
 */
public class MediaCodecVideoEncoder implements VideoEncoder {

    private static final String MIME_AVC = "video/avc";
    private static final int CONFIGURE_FLAG_ENCODE = 1;
    private static final int MUXER_OUTPUT_MPEG_4 = 0;
    private static final int INFO_TRY_AGAIN_LATER = -1;
    private static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    private static final int BUFFER_FLAG_KEY_FRAME = 1;
    private static final int BUFFER_FLAG_END_OF_STREAM = 4;
    private static final int COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    /**
     * Default bits per pixel per frame, a common heuristic for choosing an H.264 bitrate from the
     * resolution and frame rate. Roughly corresponds to visually transparent quality for
     * screen-captured content.
     */
    private static final double DEFAULT_BITS_PER_PIXEL = 0.10;

    private final Yuv420Converter converter = new Yuv420Converter();

    private VideoEncoderConfig config;
    private Object codec;
    private Object muxer;
    private Object bufferInfo;
    private File temporaryFile;
    private File muxerFile;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private boolean opened;
    private boolean finished;
    private long frameCount;

    // Reflected members. Resolution is tied to the class loader it was performed against, so a
    // different context class loader (as used by tests to supply stand-in Android classes) causes a
    // re-resolve rather than silently reusing members belonging to another loader.
    private static volatile AndroidApi androidApi;
    private static volatile ClassLoader androidApiLoader;
    private static volatile boolean androidApiResolved;

    /**
     * The reflected Android entry points, resolved together so they always belong to one loader.
     */
    private static class AndroidApi {
        final Method createEncoderByType, configure, start, stop, release, dequeueInputBuffer,
                getInputBuffer, queueInputBuffer, dequeueOutputBuffer, getOutputBuffer,
                releaseOutputBuffer, signalEndOfInputStream, getOutputFormat, addTrack, muxerStart,
                writeSampleData, muxerStop, muxerRelease, setInteger, setString;
        final Constructor<?> bufferInfoCtor;
        final Field offsetField, sizeField, presentationTimeField, flagsField;

        AndroidApi(ClassLoader loader) throws Exception {
            Class<?> codecClass = androidClass(loader, "android.media.MediaCodec");
            Class<?> formatClass = androidClass(loader, "android.media.MediaFormat");
            Class<?> muxerClass = androidClass(loader, "android.media.MediaMuxer");
            Class<?> infoClass = androidClass(loader, "android.media.MediaCodec$BufferInfo");

            createEncoderByType = codecClass.getMethod("createEncoderByType", String.class);
            configure = codecClass.getMethod("configure", formatClass,
                    androidClass(loader, "android.view.Surface"), Object.class, int.class);
            start = codecClass.getMethod("start");
            stop = codecClass.getMethod("stop");
            release = codecClass.getMethod("release");
            signalEndOfInputStream = codecClass.getMethod("signalEndOfInputStream");
            dequeueInputBuffer = codecClass.getMethod("dequeueInputBuffer", long.class);
            getInputBuffer = codecClass.getMethod("getInputBuffer", int.class);
            queueInputBuffer = codecClass.getMethod("queueInputBuffer",
                    int.class, int.class, int.class, long.class, int.class);
            dequeueOutputBuffer = codecClass.getMethod("dequeueOutputBuffer", infoClass, long.class);
            getOutputBuffer = codecClass.getMethod("getOutputBuffer", int.class);
            releaseOutputBuffer = codecClass.getMethod("releaseOutputBuffer", int.class, boolean.class);
            getOutputFormat = codecClass.getMethod("getOutputFormat");

            setInteger = formatClass.getMethod("setInteger", String.class, int.class);
            setString = formatClass.getMethod("setString", String.class, String.class);

            addTrack = muxerClass.getMethod("addTrack", formatClass);
            muxerStart = muxerClass.getMethod("start");
            writeSampleData = muxerClass.getMethod("writeSampleData",
                    int.class, ByteBuffer.class, infoClass);
            muxerStop = muxerClass.getMethod("stop");
            muxerRelease = muxerClass.getMethod("release");

            bufferInfoCtor = infoClass.getConstructor();
            offsetField = infoClass.getField("offset");
            sizeField = infoClass.getField("size");
            presentationTimeField = infoClass.getField("presentationTimeUs");
            flagsField = infoClass.getField("flags");
        }
    }

    /**
     * Resolves the Android classes against the given loader, honouring the thread context class
     * loader so a host application or a test can supply them.
     */
    private static Class<?> androidClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static ClassLoader currentLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : MediaCodecVideoEncoder.class.getClassLoader();
    }

    /**
     * Returns the resolved Android entry points, resolving them on first use and re-resolving if the
     * context class loader has changed since.
     *
     * @return The API, or {@code null} when Android's media stack is not reachable
     */
    private static AndroidApi api() {
        ClassLoader loader = currentLoader();
        if (!androidApiResolved || androidApiLoader != loader) {
            synchronized (MediaCodecVideoEncoder.class) {
                if (!androidApiResolved || androidApiLoader != loader) {
                    AndroidApi resolved;
                    try {
                        resolved = new AndroidApi(loader);
                    } catch (Throwable ignored) {
                        // Any failure simply means this is not Android. Class loading must keep working.
                        resolved = null;
                    }
                    androidApi = resolved;
                    androidApiLoader = loader;
                    androidApiResolved = true;
                }
            }
        }
        return androidApi;
    }

    /**
     * Returns whether this backend can run in the current environment.
     *
     * @return Whether Android's media stack is reachable
     */
    public static boolean isSupported() {
        return api() != null;
    }

    /**
     * Returns a description of why the backend is unavailable, or {@code null} when it is available.
     *
     * @return The reason, or {@code null}
     */
    public static String getUnsupportedReason() {
        if (isSupported()) return null;
        return "android.media.MediaCodec is not reachable. This backend only runs on Android;"
                + " MediaCodec is part of the Android OS, so no external encoder is needed there."
                + " On desktop, render to PNG frames and use an installed ffmpeg instead.";
    }

    @Override
    public String getName() {
        return "mediacodec";
    }

    @Override
    public void open(VideoEncoderConfig config) throws IOException {
        if (this.opened) throw new IllegalStateException("This encoder has already been opened");
        if (!isSupported()) {
            throw new UnsupportedOperationException(getUnsupportedReason());
        }
        if (config == null) throw new IllegalArgumentException("Config must not be null");

        this.config = config;

        // MediaMuxer only accepts a path, so a caller-supplied channel is served via a temporary file
        // that is copied across on finish.
        if (config.getOutputFile() != null) {
            this.muxerFile = config.getOutputFile();
        } else {
            try {
                this.temporaryFile = File.createTempFile("replaystudio-video", ".mp4");
            } catch (IOException e) {
                throw new IOException("Could not create a temporary file for MediaMuxer", e);
            }
            this.muxerFile = this.temporaryFile;
        }

        try {
            this.codec = invoke(api().createEncoderByType, null, MIME_AVC);
            this.bufferInfo = newInstance(api().bufferInfoCtor);

            Object format = newFormat(MIME_AVC);
            int width = config.getWidth(), height = config.getHeight();
            int fps = (int) Math.max(1, Math.round(config.getFramesPerSecond()));
            int bitrate = estimateBitrate(width, height, config.getFramesPerSecond());

            setIntegerValue(format, "width", width);
            setIntegerValue(format, "height", height);
            setIntegerValue(format, "bitrate", bitrate);
            setIntegerValue(format, "frame-rate", fps);
            setIntegerValue(format, "i-frame-interval", 1);
            setIntegerValue(format, "color-format", COLOR_FORMAT_YUV420_FLEXIBLE);
            setStringValue(format, "mime", MIME_AVC);

            invoke(api().configure, this.codec, format, null, null, CONFIGURE_FLAG_ENCODE);
            invoke(api().start, this.codec);

            this.muxer = newMuxer(this.muxerFile.getAbsolutePath());
            this.opened = true;
        } catch (IOException e) {
            cleanupAfterFailedOpen();
            throw e;
        } catch (RuntimeException e) {
            cleanupAfterFailedOpen();
            throw e;
        } catch (Exception e) {
            cleanupAfterFailedOpen();
            throw new IOException("Could not start the MediaCodec encoder", e);
        }
    }

    @Override
    public void writeFrame(VideoFrame frame) throws IOException {
        if (this.finished) throw new IllegalStateException("This encoder has already been finished");
        if (!this.opened) throw new IllegalStateException("This encoder has not been opened yet");
        if (frame == null) throw new IllegalArgumentException("Frame must not be null");

        VideoEncoderConfig cfg = this.config;
        if (frame.getWidth() != cfg.getWidth() || frame.getHeight() != cfg.getHeight()) {
            throw new IllegalArgumentException("Frame is " + frame.getWidth() + "x" + frame.getHeight()
                    + " but the encoder was opened for " + cfg.getWidth() + "x" + cfg.getHeight()
                    + "; a container cannot change resolution mid-stream");
        }
        if (frame.getFormat() == PixelFormat.YUV420) {
            throw new IllegalArgumentException("YUV420 input is not supported; supply an RGB format"
                    + " and let Yuv420Converter do the colour conversion");
        }

        byte[] i420 = converter.toI420(frame);
        long pts = frame.getPresentationTimeMicros() > 0
                ? frame.getPresentationTimeMicros()
                : (long) (this.frameCount * 1_000_000L * cfg.getFramesPerSecondDenominator()
                / cfg.getFramesPerSecondNumerator());

        try {
            feedFrame(i420, pts);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MediaCodec failed while accepting frame " + this.frameCount, e);
        }

        this.frameCount++;
        try {
            drain(false);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MediaCodec failed while draining output", e);
        }
    }

    /** Dequeues an input buffer, retries while none is free, then submits the frame. */
    private void feedFrame(byte[] i420, long pts) throws Exception {
        int index;
        while ((index = (Integer) invoke(api().dequeueInputBuffer, this.codec, DEQUEUE_TIMEOUT_US)) < 0) {
            // No input buffer available, so let the codec produce output first.
            drain(false);
            index = (Integer) invoke(api().dequeueInputBuffer, this.codec, DEQUEUE_TIMEOUT_US);
            if (index < 0) {
                throw new IOException("The encoder never released an input buffer");
            }
        }

        ByteBuffer buffer = (ByteBuffer) invoke(api().getInputBuffer, this.codec, index);
        if (buffer == null) throw new IOException("MediaCodec returned no buffer for input index " + index);
        buffer.clear();
        buffer.put(i420);
        invoke(api().queueInputBuffer, this.codec, index, 0, i420.length, pts, 0);
    }

    /**
     * Moves any available encoded samples into the muxer.
     *
     * @param untilEndOfStream When true, keep draining until the codec reports end of stream
     */
    private void drain(boolean untilEndOfStream) throws Exception {
        int idlePolls = 0;
        while (true) {
            int index = (Integer) invoke(api().dequeueOutputBuffer, this.codec, this.bufferInfo, DEQUEUE_TIMEOUT_US);

            if (index == INFO_OUTPUT_FORMAT_CHANGED) {
                if (this.muxerStarted) {
                    throw new IllegalStateException("The encoder changed output format after muxing started");
                }
                Object outputFormat = invoke(api().getOutputFormat, this.codec);
                this.trackIndex = (Integer) invoke(api().addTrack, this.muxer, outputFormat);
                invoke(api().muxerStart, this.muxer);
                this.muxerStarted = true;
                idlePolls = 0;
                continue;
            }

            if (index < 0) {
                // INFO_TRY_AGAIN_LATER, or an informational code this library does not handle.
                if (!untilEndOfStream) return;
                // Cap the spin so a wedged codec surfaces as an error instead of hanging the renderer.
                if (++idlePolls > MAX_IDLE_POLLS) {
                    throw new IOException("The encoder stalled before signalling end of stream after "
                            + MAX_IDLE_POLLS + " polls");
                }
                continue;
            }

            idlePolls = 0;

            int flags = api().flagsField.getInt(this.bufferInfo);
            int size = api().sizeField.getInt(this.bufferInfo);
            int offset = api().offsetField.getInt(this.bufferInfo);

            if (size > 0 && this.muxerStarted) {
                ByteBuffer encoded = (ByteBuffer) invoke(api().getOutputBuffer, this.codec, index);
                if (encoded != null) {
                    encoded.position(offset);
                    encoded.limit(offset + size);
                    invoke(api().writeSampleData, this.muxer, this.trackIndex, encoded, this.bufferInfo);
                }
            }

            invoke(api().releaseOutputBuffer, this.codec, index, false);

            if ((flags & BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private static final int MAX_IDLE_POLLS = 1000;

    @Override
    public void finish() throws IOException {
        if (this.finished) return;
        this.finished = true;

        if (!this.opened) return;

        try {
            // Signal end of input, then drain the remaining samples out of the encoder.
            invoke(api().signalEndOfInputStream, this.codec);
            drain(true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MediaCodec failed while finishing", e);
        } finally {
            if (this.muxerStarted) {
                try {
                    invoke(api().muxerStop, this.muxer);
                } catch (Exception ignored) {
                    // Reported below through the copy step if the file is unusable.
                }
            }
            releaseQuietly(api().muxerRelease, this.muxer);
            releaseQuietly(api().release, this.codec);
            this.muxer = null;
            this.codec = null;

            if (this.temporaryFile != null) {
                try {
                    copyToChannel(this.temporaryFile, this.config.getOutputChannel());
                } finally {
                    deleteQuietly(this.temporaryFile);
                }
            }
        }
    }

    @Override
    public boolean isOpen() {
        return this.opened && !this.finished;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public long getFrameCount() {
        return this.frameCount;
    }

    @Override
    public void close() throws IOException {
        finish();
    }

    private void cleanupAfterFailedOpen() {
        releaseQuietly(api().muxerRelease, this.muxer);
        releaseQuietly(api().release, this.codec);
        this.muxer = null;
        this.codec = null;
        if (this.temporaryFile != null) {
            deleteQuietly(this.temporaryFile);
            this.temporaryFile = null;
        }
    }

    /**
     * Estimates an H.264 bitrate from the resolution and frame rate.
     *
     * @param width Frame width
     * @param height Frame height
     * @param framesPerSecond Frame rate
     * @return Bitrate in bits per second
     */
    static int estimateBitrate(int width, int height, double framesPerSecond) {
        long estimate = (long) (width * (long) height * framesPerSecond * DEFAULT_BITS_PER_PIXEL);
        // Keep it inside a range every hardware encoder will accept.
        return (int) Math.max(64_000L, Math.min(estimate, 100_000_000L));
    }

    private static void copyToChannel(File file, java.nio.channels.FileChannel channel) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file);
             OutputStream out = java.nio.channels.Channels.newOutputStream(channel)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static void releaseQuietly(Method method, Object target) {
        if (method == null || target == null) return;
        try {
            method.invoke(target);
        } catch (Throwable ignored) {
            // Nothing useful to do while unwinding.
        }
    }

    private static Object newMuxer(String path) throws Exception {
        Class<?> muxerClass = Class.forName("android.media.MediaMuxer");
        return muxerClass.getConstructor(String.class, int.class)
                .newInstance(path, MUXER_OUTPUT_MPEG_4);
    }

    private static Object newFormat(String mime) throws Exception {
        Class<?> formatClass = Class.forName("android.media.MediaFormat");
        return formatClass.getConstructor(String.class).newInstance(mime);
    }

    private static void setIntegerValue(Object format, String key, int value) throws Exception {
        invoke(api().setInteger, format, key, value);
    }

    private static void setStringValue(Object format, String key, String value) throws Exception {
        invoke(api().setString, format, key, value);
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        return method.invoke(target, args);
    }

    private static Object newInstance(Constructor<?> constructor) throws Exception {
        return constructor.newInstance();
    }
}
