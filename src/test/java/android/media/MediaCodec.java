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
package android.media;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test stand-in for {@code android.media.MediaCodec}, modelling the parts of the real asynchronous
 * contract that the encoder in this library has to get right.
 *
 * <p>Deliberately behaves like the real thing rather than like a stub: input buffers are only handed
 * out when free, encoded samples appear a couple of polls after the corresponding input is queued, the
 * output format is announced once and only once, and end of stream only arrives after
 * {@link #signalEndOfInputStream()}. Getting those interactions wrong is exactly the class of bug this
 * fake exists to catch.</p>
 *
 * <p>The recorded {@link #inputs} and {@link #outputs} let a test assert on exactly what the encoder
 * fed in and what it muxed out.</p>
 */
public class MediaCodec {

    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    public static final int BUFFER_FLAG_KEY_FRAME = 1;
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;

    /** Number of output polls a queued input takes to surface as an encoded sample. */
    private static final int ENCODING_LATENCY_POLLS = 1;

    /** One sample the encoder handed over. */
    public static class Encoded {
        public final byte[] data;
        public final long presentationTimeUs;
        public final boolean keyFrame;

        Encoded(byte[] data, long presentationTimeUs, boolean keyFrame) {
            this.data = data;
            this.presentationTimeUs = presentationTimeUs;
            this.keyFrame = keyFrame;
        }
    }

    /** Everything the encoder configured, for assertions. */
    public static MediaCodec lastCreated;

    public MediaFormat configuredFormat;
    public int configureFlags;
    public boolean started, stopped, released;
    public int signalEndOfStreamCalls;
    public final List<String> releasedOutputBuffers = new ArrayList<>();
    public final List<byte[]> inputs = new ArrayList<>();
    public final List<Long> inputTimestamps = new ArrayList<>();

    private final Deque<Integer> freeInputIndices = new ArrayDeque<>();
    private final java.util.Set<Integer> checkedOut = new java.util.HashSet<>();
    private final Deque<Encoded> pending = new ArrayDeque<>();
    private final ByteBuffer[] inputBuffers = new ByteBuffer[4];
    private final ByteBuffer[] outputBuffers = new ByteBuffer[64];
    private boolean formatAnnounced;
    private boolean endOfStreamSignalled;
    private int pollsUntilReady;
    private int nextOutputIndex;

    public static MediaCodec createEncoderByType(String mime) {
        if (!"video/avc".equals(mime)) throw new IllegalArgumentException(mime);
        MediaCodec codec = new MediaCodec();
        lastCreated = codec;
        return codec;
    }

    public void configure(MediaFormat format, android.view.Surface surface, Object crypto, int flags) {
        if (surface != null) throw new IllegalArgumentException("this fake only accepts a null surface");
        this.configureFlags = flags;
        this.configuredFormat = format;
        // Real codecs hand out a fixed pool of input buffers after start(). Each one must be stable,
        // because the caller writes into it and then hands the same index back to queueInputBuffer.
        for (int i = 0; i < 4; i++) {
            this.freeInputIndices.add(i);
            this.inputBuffers[i] = ByteBuffer.allocateDirect(Math.max(1, expectedInputSize()));
        }
    }

    public void start() {
        this.started = true;
    }

    public void stop() {
        this.stopped = true;
    }

    public void release() {
        this.released = true;
    }

    public void signalEndOfInputStream() {
        this.signalEndOfStreamCalls++;
        this.endOfStreamSignalled = true;
    }

    public MediaFormat getOutputFormat() {
        if (!this.formatAnnounced) throw new IllegalStateException("format not ready");
        return this.configuredFormat;
    }

    public int dequeueInputBuffer(long timeoutUs) {
        Integer index = this.freeInputIndices.poll();
        if (index == null) return INFO_TRY_AGAIN_LATER;
        // The buffer now belongs to the caller until it is handed back via queueInputBuffer.
        this.checkedOut.add(index);
        return index;
    }

    public ByteBuffer getInputBuffer(int index) {
        return this.inputBuffers[index];
    }

    public void queueInputBuffer(int index, int offset, int size, long pts, int flags) {
        if (!this.checkedOut.remove(index)) {
            throw new IllegalStateException("buffer " + index + " was not dequeued by this codec");
        }

        ByteBuffer source = this.inputBuffers[index];
        byte[] copy = new byte[size];
        int from = offset;
        for (int i = 0; i < size; i++) {
            copy[i] = source.get(from + i);
        }
        this.inputs.add(copy);
        this.inputTimestamps.add(pts);
        this.freeInputIndices.add(index);

        // Produce a fake encoded sample shortly afterwards, echoing the input size and timestamp.
        this.pending.add(new Encoded(new byte[size], pts, this.inputs.size() == 1));
        this.pollsUntilReady = ENCODING_LATENCY_POLLS;
    }

    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
        if (!this.formatAnnounced) {
            this.formatAnnounced = true;
            return INFO_OUTPUT_FORMAT_CHANGED;
        }
        if (this.pollsUntilReady > 0) {
            this.pollsUntilReady--;
            return INFO_TRY_AGAIN_LATER;
        }
        Encoded sample = this.pending.poll();
        if (sample == null) {
            if (this.endOfStreamSignalled) {
                // A real codec reports end of stream even when no frames were ever submitted.
                info.offset = 0;
                info.size = 0;
                info.presentationTimeUs = 0;
                info.flags = BUFFER_FLAG_END_OF_STREAM;
                return 0;
            }
            return INFO_TRY_AGAIN_LATER;
        }

        int index = this.nextOutputIndex++;
        ByteBuffer buffer = this.outputBuffers[index];
        if (buffer == null || buffer.capacity() < sample.data.length) {
            buffer = ByteBuffer.allocateDirect(Math.max(64, sample.data.length));
            this.outputBuffers[index] = buffer;
        }
        buffer.clear();
        buffer.put(sample.data);
        buffer.flip();

        info.offset = 0;
        info.size = sample.data.length;
        info.presentationTimeUs = sample.presentationTimeUs;
        info.flags = sample.keyFrame ? BUFFER_FLAG_KEY_FRAME : 0;
        return index;
    }

    public ByteBuffer getOutputBuffer(int index) {
        return this.outputBuffers[index];
    }

    public void releaseOutputBuffer(int index, boolean render) {
        this.releasedOutputBuffers.add(String.valueOf(index));
    }

    private int expectedInputSize() {
        if (this.configuredFormat == null) return 1;
        int width = this.configuredFormat.getInteger("width");
        int height = this.configuredFormat.getInteger("height");
        return width * height * 3 / 2;
    }

    /** Mirrors the real nested type, so its binary name is {@code android.media.MediaCodec$BufferInfo}. */
    public static class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
    }
}