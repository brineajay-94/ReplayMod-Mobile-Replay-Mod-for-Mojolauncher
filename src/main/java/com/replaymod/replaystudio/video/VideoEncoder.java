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

import java.io.Closeable;
import java.io.IOException;

/**
 * Encodes raw video frames into a compressed file, without requiring any installed encoder binary.
 *
 * <p>The purpose of this interface is to let a replay be rendered straight to a video file on a machine
 * that has no {@code ffmpeg} installed, which includes Android where installing one is not possible.
 * Implementations are discovered through
 * {@link com.replaymod.replaystudio.Studio#loadVideoEncoder(String)} just like {@code StreamFilter}s
 * are.</p>
 *
 * <p>Backends are platform-specific: {@link com.replaymod.replaystudio.video.MediaCodecVideoEncoder}
 * uses Android's hardware encoder, {@link JcodecVideoEncoder} is pure Java but currently disabled
 * because it produces the wrong colour. A backend that cannot run in the current environment should
 * fail from {@link #open(VideoEncoderConfig)} with a clear message rather than being discovered as
 * usable.</p>
 *
 * <p>Lifecycle is {@link #open(VideoEncoderConfig)}, then any number of {@link #writeFrame(VideoFrame)}
 * calls, then {@link #finish()}. {@link #close()} calls {@link #finish()} if it has not been called, so
 * a try-with-resources block produces a playable file even on an early exit, and closing more than
 * once is harmless.</p>
 *
 * <p>Implementations are not required to be thread safe. Frames must be supplied in presentation
 * order.</p>
 *
 * @see VideoFrame
 * @see FrameSink
 */
public interface VideoEncoder extends Closeable {

    /**
     * Returns a simple, unique and stable name for this encoder, used to look it up by name.
     *
     * @return The name
     */
    String getName();

    /**
     * Prepares this encoder to write a video described by the given configuration.
     *
     * <p>Must be called before {@link #writeFrame(VideoFrame)} and may only be called once.</p>
     *
     * @param config The output location, dimensions, frame rate and quality
     * @throws IOException If the output cannot be opened
     */
    void open(VideoEncoderConfig config) throws IOException;

    /**
     * Encodes a single frame.
     *
     * <p>The frame's dimensions and format must match what {@link #open(VideoEncoderConfig)} was
     * given. The frame's pixel array is only read during this call and is not retained, so the caller
     * may recycle it as soon as this method returns.</p>
     *
     * @param frame The frame to encode
     * @throws IOException If encoding fails
     * @throws IllegalStateException If the encoder is not open or is already finished
     */
    void writeFrame(VideoFrame frame) throws IOException;

    /**
     * Flushes all buffered data and finalises the container so the file becomes playable.
     *
     * <p>Safe to call more than once; subsequent calls do nothing. After this the encoder accepts no
     * further frames.</p>
     *
     * @throws IOException If the output cannot be finalised
     */
    void finish() throws IOException;

    /**
     * Returns whether {@link #open(VideoEncoderConfig)} has been called and {@link #finish()} has not.
     *
     * @return Whether frames may currently be written
     */
    boolean isOpen();

    /**
     * Returns whether the container has been finalised.
     *
     * @return Whether encoding has finished
     */
    boolean isFinished();

    /**
     * Returns the number of frames successfully written so far.
     *
     * @return The frame count
     */
    long getFrameCount();
}