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

import java.io.IOException;

/**
 * Receiver of encoded frames, implemented by whoever drives the encoder.
 *
 * <p>This is the seam between a renderer and a {@link VideoEncoder}. A renderer walks a replay, draws
 * each frame and hands the pixels to a sink; the sink knows nothing about H.264, MP4 or any specific
 * backend. That keeps the encoder swappable, so the same renderer can target the built-in pure-Java
 * encoder on a device with no {@code ffmpeg} and a hardware-accelerated one on a desktop without any
 * change to the rendering code.</p>
 *
 * @see VideoEncoder
 */
public interface FrameSink {

    /**
     * Returns a sink that forwards every frame to the given encoder.
     *
     * @param encoder The encoder to write to
     * @return The sink
     */
    static FrameSink forEncoder(VideoEncoder encoder) {
        return new Of(encoder);
    }

    /**
     * Offers a frame to this sink.
     *
     * <p>The frame's pixel array is not retained past this call unless the implementation explicitly
     * copies it.</p>
     *
     * @param frame The frame
     * @throws IOException If writing the frame fails
     */
    void accept(VideoFrame frame) throws IOException;

    /**
     * A {@link FrameSink} that forwards straight to a {@link VideoEncoder}.
     *
     * <p>Returned by {@link FrameSink#forEncoder(VideoEncoder)}. Keeping this as a separate type
     * rather than having {@link VideoEncoder} extend it is deliberate: the encoder must never be able to
     * hand a frame back to its producer.</p>
     */
    final class Of implements FrameSink {

        private final VideoEncoder encoder;

        public Of(VideoEncoder encoder) {
            if (encoder == null) throw new IllegalArgumentException("Encoder must not be null");
            this.encoder = encoder;
        }

        /**
         * Returns the encoder this sink writes to.
         *
         * @return The encoder
         */
        public VideoEncoder getEncoder() {
            return this.encoder;
        }

        @Override
        public void accept(VideoFrame frame) throws IOException {
            this.encoder.writeFrame(frame);
        }

        @Override
        public String toString() {
            return "FrameSink.Of(" + this.encoder.getName() + ")";
        }
    }
}