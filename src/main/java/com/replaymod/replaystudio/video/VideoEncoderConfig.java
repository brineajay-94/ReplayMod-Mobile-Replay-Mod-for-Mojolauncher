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
import java.nio.channels.FileChannel;

/**
 * Settings for a {@link VideoEncoder}.
 *
 * <p>Every frame handed to the encoder must match the width, height and format declared here, since
 * the container header is written before the first frame and cannot describe a mid-stream resolution
 * change.</p>
 *
 * <p>The output must be <em>seekable</em>, because muxing MP4 means rewriting box sizes once their
 * contents are known. That rules out a plain {@link java.io.OutputStream}: use a {@link File}, or a
 * {@link FileChannel} when the video belongs somewhere other than a standalone file, such as inside
 * another container. {@link FileChannel} is available on Android from API 1, unlike
 * {@code java.nio.file}.</p>
 *
 * <p>Built with {@link #builder()} rather than a long constructor, since the required and optional
 * parts are not obvious from the argument list alone.</p>
 */
public class VideoEncoderConfig {

    private final File outputFile;
    private final FileChannel outputChannel;
    private final int width, height;
    private final int framesPerSecondNumerator, framesPerSecondDenominator;
    private final int quality;

    private VideoEncoderConfig(Builder builder) {
        this.outputFile = builder.outputFile;
        this.outputChannel = builder.outputChannel;
        this.width = builder.width;
        this.height = builder.height;
        this.framesPerSecondNumerator = builder.framesPerSecondNumerator;
        this.framesPerSecondDenominator = builder.framesPerSecondDenominator;
        this.quality = builder.quality;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the file to write to, if the encoder was configured with a file.
     *
     * @return The output file, or {@code null} if writing to a channel
     */
    public File getOutputFile() {
        return this.outputFile;
    }

    /**
     * Returns the channel to write to, if the encoder was configured with a channel.
     *
     * @return The output channel, or {@code null} if writing to a file
     */
    public FileChannel getOutputChannel() {
        return this.outputChannel;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    /**
     * Returns the frame rate numerator.
     *
     * <p>Together with {@link #getFramesPerSecondDenominator()} this expresses the exact rate, so
     * {@code 30000/1001} is expressible and does not have to be rounded to 30.</p>
     *
     * @return The numerator
     */
    public int getFramesPerSecondNumerator() {
        return this.framesPerSecondNumerator;
    }

    public int getFramesPerSecondDenominator() {
        return this.framesPerSecondDenominator;
    }

    /**
     * Returns the frame rate as a floating point value, for display and logging only. Container
     * timing uses the exact rational form.
     *
     * @return The frame rate in frames per second
     */
    public double getFramesPerSecond() {
        return (double) this.framesPerSecondNumerator / this.framesPerSecondDenominator;
    }

    /**
     * Returns the requested quality, from {@code 1} (smallest, worst) to {@code 100} (largest, best).
     *
     * <p>Not every backend honours this. The built-in pure-Java encoder does not expose rate control,
     * so it uses its own fixed quality and ignores this value.</p>
     *
     * @return The quality
     */
    public int getQuality() {
        return this.quality;
    }

    @Override
    public String toString() {
        return "VideoEncoderConfig(" + this.width + "x" + this.height + " @ "
                + this.framesPerSecondNumerator + "/" + this.framesPerSecondDenominator + "fps, q="
                + this.quality + ", out="
                + (this.outputFile != null ? this.outputFile.getName() : "channel") + ")";
    }

    /**
     * Builder for {@link VideoEncoderConfig}.
     */
    public static class Builder {
        private File outputFile;
        private FileChannel outputChannel;
        private int width, height;
        private int framesPerSecondNumerator = 30, framesPerSecondDenominator = 1;
        private int quality = 90;

        private Builder() {}

        /**
         * Sets the file to write the video to. The encoder creates and owns the file.
         *
         * @param file The output file
         * @return This builder
         */
        public Builder output(File file) {
            if (file == null) throw new IllegalArgumentException("File must not be null");
            this.outputFile = file;
            this.outputChannel = null;
            return this;
        }

        /**
         * Sets the channel to write the video to. The caller keeps ownership and is responsible for
         * closing it.
         *
         * @param channel A seekable, writable channel
         * @return This builder
         */
        public Builder output(FileChannel channel) {
            if (channel == null) throw new IllegalArgumentException("Channel must not be null");
            this.outputChannel = channel;
            this.outputFile = null;
            return this;
        }

        /**
         * Sets the frame dimensions, which every frame must match. Both must be even, since H.264 uses
         * 4:2:0 chroma subsampling.
         *
         * @param width Width in pixels
         * @param height Height in pixels
         * @return This builder
         */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Sets an exact integer frame rate.
         *
         * @param framesPerSecond Frames per second, must be positive
         * @return This builder
         */
        public Builder frameRate(int framesPerSecond) {
            return frameRate(framesPerSecond, 1);
        }

        /**
         * Sets an exact rational frame rate, for rates that are not whole numbers such as 30000/1001.
         *
         * @param numerator Frame rate numerator, must be positive
         * @param denominator Frame rate denominator, must be positive
         * @return This builder
         */
        public Builder frameRate(int numerator, int denominator) {
            if (numerator <= 0) throw new IllegalArgumentException("Frame rate numerator must be positive");
            if (denominator <= 0) throw new IllegalArgumentException("Frame rate denominator must be positive");
            this.framesPerSecondNumerator = numerator;
            this.framesPerSecondDenominator = denominator;
            return this;
        }

        /**
         * Sets the requested quality.
         *
         * @param quality Quality from {@code 1} to {@code 100}
         * @return This builder
         */
        public Builder quality(int quality) {
            if (quality < 1 || quality > 100) {
                throw new IllegalArgumentException("Quality must be within [1, 100] but was " + quality);
            }
            this.quality = quality;
            return this;
        }

        /**
         * Validates and builds the configuration.
         *
         * @return The configuration
         * @throws IllegalStateException If required fields are missing or invalid
         */
        public VideoEncoderConfig build() {
            if (this.outputFile == null && this.outputChannel == null) {
                throw new IllegalStateException("An output file or output channel is required");
            }
            if (this.width <= 0 || this.height <= 0) {
                throw new IllegalStateException("Frame size must be set to a positive width and height");
            }
            if ((this.width & 1) != 0 || (this.height & 1) != 0) {
                throw new IllegalStateException("H.264 uses 4:2:0 chroma subsampling, so both dimensions must be"
                        + " even, but got " + this.width + "x" + this.height);
            }
            return new VideoEncoderConfig(this);
        }
    }
}