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

import com.replaymod.replaystudio.lib.jcodec.api.SequenceEncoder;
import com.replaymod.replaystudio.lib.jcodec.common.Codec;
import com.replaymod.replaystudio.lib.jcodec.common.Format;
import com.replaymod.replaystudio.lib.jcodec.common.io.FileChannelWrapper;
import com.replaymod.replaystudio.lib.jcodec.common.io.NIOUtils;
import com.replaymod.replaystudio.lib.jcodec.common.model.ColorSpace;
import com.replaymod.replaystudio.lib.jcodec.common.model.Picture;
import com.replaymod.replaystudio.lib.jcodec.common.model.Rational;

import java.io.Closeable;
import java.io.IOException;

/**
 * {@link VideoEncoder} built on JCodec, producing H.264 in an MP4 container in pure Java.
 *
 * <p><b>This backend is currently disabled because it produces the wrong colours.</b> See
 * {@link #DEFECT} for the full findings. It is not registered as a service, so
 * {@link com.replaymod.replaystudio.Studio#loadVideoEncoder(String)} will not return it, and
 * {@link #open(VideoEncoderConfig)} refuses to run unless explicitly overridden. It is kept in the
 * tree so the investigation is not lost and so it can be re-enabled the moment a correct path exists.</p>
 *
 * <p>The rest of this class is functional and correct: muxing, framing, frame counts and the
 * per-pixel format mapping were all verified. Only the absolute colour is wrong.</p>
 *
 * <p><b>Performance and quality.</b> Even once colour is fixed, JCodec's H.264 encoder is baseline
 * profile with simple rate control, and its own documentation states that output quality and bitrate
 * are markedly worse than native encoders such as x264, and that encoding is roughly an order of
 * magnitude slower. It would be the "it always works" option, never the "fastest export" option.</p>
 *
 * <p><b>Audio is not supported.</b> There is no viable pure-Java AAC encoder, so output is
 * video-only.</p>
 *
 * <p><b>Portability.</b> Only JCodec's own {@code org.jcodec.common.io.SeekableByteChannel} is used,
 * never {@code java.nio.channels.SeekableByteChannel}, which does not exist on Android before API 24.
 * Output goes through {@link NIOUtils} and {@link FileChannelWrapper}, both of which are built on
 * {@code java.io.File} and {@code java.nio.channels.FileChannel} and so are available from API 1.</p>
 */
public class JcodecVideoEncoder implements VideoEncoder {

    /**
     * Documents why this backend is disabled, so the decision is discoverable from the code.
     */
    public static final String DEFECT =
            "JCodec 0.2.5 produces the wrong colour. Verified against FFmpeg 8.1.1: for a pure red"
                    + " (220,20,20) frame the correct BT.601 limited-range centre values are"
                    + " Y=85 U=98 V=216, but JCodec's internal RGB to YUV conversion emits"
                    + " Y=251 U=9 V=228, so luma is outside even the legal 16-235 range. Decoding the"
                    + " resulting file with FFmpeg returns red as cyan, green as magenta and blue as"
                    + " yellow. Bypassing JCodec's transform and feeding it hand-computed YUV420J"
                    + " planes through its low-level org.jcodec.codecs.h264.H264Encoder plus"
                    + " org.jcodec.api.transcode.SinkImpl produces equally incorrect output, so the"
                    + " defect is not confined to RgbToYuv420j. The container, frame count and"
                    + " per-pixel format mapping are all correct; only colour is wrong. JCodec has had"
                    + " essentially no releases since 0.2.5 in 2019.";

    /**
     * System property that re-enables this backend despite {@link #DEFECT}, for experimentation only.
     */
    public static final String OVERRIDE_PROPERTY = "replaystudio.video.allowBrokenJcodec";

    private VideoEncoderConfig config;
    private SequenceEncoder encoder;
    private FileChannelWrapper channel;
    private boolean ownsChannel;
    private boolean opened;
    private boolean finished;
    private long frameCount;

    /**
     * Reusable staging picture, reallocated only if the declared size changes, to avoid one allocation
     * per frame.
     */
    private Picture picture;

    @Override
    public String getName() {
        return "jcodec";
    }

    @Override
    public void open(VideoEncoderConfig config) throws IOException {
        if (!Boolean.getBoolean(OVERRIDE_PROPERTY)) {
            throw new UnsupportedOperationException(
                    "The jcodec encoder is disabled because it produces incorrect colours. " + DEFECT);
        }
        if (this.opened) throw new IllegalStateException("This encoder has already been opened");
        if (config == null) throw new IllegalArgumentException("Config must not be null");

        Rational fps = Rational.R(config.getFramesPerSecondNumerator(), config.getFramesPerSecondDenominator());

        if (config.getOutputFile() != null) {
            this.channel = NIOUtils.writableChannel(config.getOutputFile());
            this.ownsChannel = true;
        } else {
            // Wrap, but do not own: the caller keeps the channel and closes it.
            this.channel = new FileChannelWrapper(config.getOutputChannel());
            this.ownsChannel = false;
        }

        try {
            this.encoder = new SequenceEncoder(this.channel, fps, Format.MOV, Codec.H264, null);
        } catch (IOException | RuntimeException e) {
            // Never leak a channel we opened if the muxer could not be set up.
            if (this.ownsChannel) closeQuietly(this.channel);
            this.channel = null;
            throw e;
        }

        this.config = config;
        this.opened = true;
    }

    @Override
    public void writeFrame(VideoFrame frame) throws IOException {
        if (this.finished) throw new IllegalStateException("This encoder has already been finished");
        if (!this.opened || this.encoder == null) {
            throw new IllegalStateException("This encoder has not been opened yet");
        }
        if (frame == null) throw new IllegalArgumentException("Frame must not be null");

        VideoEncoderConfig cfg = this.config;
        if (frame.getWidth() != cfg.getWidth() || frame.getHeight() != cfg.getHeight()) {
            throw new IllegalArgumentException("Frame is " + frame.getWidth() + "x" + frame.getHeight()
                    + " but the encoder was opened for " + cfg.getWidth() + "x" + cfg.getHeight()
                    + "; a container cannot change resolution mid-stream");
        }
        if (frame.getFormat() == PixelFormat.YUV420) {
            throw new IllegalArgumentException("YUV420 input is not supported yet; supply ARGB8888, RGB888,"
                    + " RGB24, BGR24 or RGBA and let the encoder convert");
        }

        Picture target = this.picture;
        if (target == null) {
            target = Picture.create(frame.getWidth(), frame.getHeight(), ColorSpace.RGB);
            this.picture = target;
        }
        convert(frame, target.getData()[0]);
        this.encoder.encodeNativeFrame(target);
        this.frameCount++;
    }

    /**
     * Writes the frame's pixels into the encoder's interleaved 8-bit RGB plane, dropping alpha.
     *
     * <p>{@link ColorSpace#RGB} is interleaved with three components, so its single plane is exactly
     * {@code width * height * 3} bytes with no row padding. That makes this a straight per-pixel
     * repack with no stride arithmetic.</p>
     */
    private static void convert(VideoFrame frame, byte[] plane) {
        byte[] src = frame.getData();
        int pixels = frame.getWidth() * frame.getHeight();

        switch (frame.getFormat()) {
            case RGB24:
                // Already R, G, B.
                System.arraycopy(src, 0, plane, 0, pixels * 3);
                return;
            case BGR24:
                for (int i = 0, s = 0, d = 0; i < pixels; i++, s += 3, d += 3) {
                    plane[d] = src[s + 2];
                    plane[d + 1] = src[s + 1];
                    plane[d + 2] = src[s];
                }
                return;
            case ARGB8888:
                // An 0xAARRGGBB int is B, G, R, A in memory, so skip the first byte and copy three.
                for (int i = 0, s = 0, d = 0; i < pixels; i++, s += 4, d += 3) {
                    plane[d] = src[s + 2];
                    plane[d + 1] = src[s + 1];
                    plane[d + 2] = src[s];
                }
                return;
            case RGBA:
            case RGB888:
                // R, G, B occupy the first three bytes in both layouts.
                for (int i = 0, s = 0, d = 0; i < pixels; i++, s += 4, d += 3) {
                    plane[d] = src[s];
                    plane[d + 1] = src[s + 1];
                    plane[d + 2] = src[s + 2];
                }
                return;
            default:
                throw new IllegalArgumentException("Unhandled pixel format " + frame.getFormat());
        }
    }

    @Override
    public void finish() throws IOException {
        if (this.finished) return;
        this.finished = true;

        SequenceEncoder toFinish = this.encoder;
        FileChannelWrapper toClose = this.channel;
        this.encoder = null;
        this.channel = null;

        if (toFinish == null) return;

        try {
            toFinish.finish();
        } finally {
            if (this.ownsChannel && toClose != null) {
                closeQuietly(toClose);
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

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do while unwinding from a failure.
        }
    }
}