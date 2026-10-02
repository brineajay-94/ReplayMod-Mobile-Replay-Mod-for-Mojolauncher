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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in for {@code android.media.MediaMuxer}, writing a real file so the encoder's output
 * handling can be checked end to end.
 *
 * <p>Samples are accumulated into a simple length-prefixed binary file rather than a real MP4, since
 * these tests verify the encoder's interaction with the muxer API, not MP4 structure.</p>
 */
public class MediaMuxer {

    public static final int MUXER_OUTPUT_MPEG_4 = 0;

    /** Every muxer constructed during a test, so assertions can reach it. */
    public static final List<MediaMuxer> all = new ArrayList<>();

    public final String path;
    public final int outputFormat;
    public boolean started, stopped, released;
    public final List<Integer> tracksAdded = new ArrayList<>();
    public final List<String> samplesWritten = new ArrayList<>();
    public final List<Integer> trackIndicesUsed = new ArrayList<>();

    private OutputStream out;
    private int bytesWritten;

    public MediaMuxer(String path, int outputFormat) throws IOException {
        this.path = path;
        this.outputFormat = outputFormat;
        if (outputFormat != MUXER_OUTPUT_MPEG_4) {
            throw new IllegalArgumentException("only MPEG_4 is supported by this fake");
        }
        this.out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(path)));
        all.add(this);
    }

    public int addTrack(MediaFormat format) {
        if (this.started) throw new IllegalStateException("cannot add a track after start");
        int index = this.tracksAdded.size();
        this.tracksAdded.add(index);
        return index;
    }

    public void start() {
        if (this.tracksAdded.isEmpty()) throw new IllegalStateException("no track was added");
        if (this.started) throw new IllegalStateException("already started");
        this.started = true;
    }

    public void writeSampleData(int trackIndex, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!this.started) throw new IllegalStateException("muxer not started");
        if (trackIndex != 0) throw new IllegalStateException("unexpected track " + trackIndex);

        int size = info.size;
        int offset = info.offset;
        byte[] sample = new byte[size];
        for (int i = 0; i < size; i++) {
            sample[i] = buffer.get(offset + i);
        }
        try {
            // A deliberately simple framing so the test can tell samples apart.
            this.out.write(new byte[]{
                    (byte) (info.flags & 0xFF),
                    (byte) ((info.presentationTimeUs >>> 24) & 0xFF),
                    (byte) ((info.presentationTimeUs >>> 16) & 0xFF),
                    (byte) ((info.presentationTimeUs >>> 8) & 0xFF),
                    (byte) (info.presentationTimeUs & 0xFF)
            });
            this.out.write(sample);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.bytesWritten += size + 5;
        this.samplesWritten.add("track=" + trackIndex + " size=" + size
                + " pts=" + info.presentationTimeUs + " flags=" + info.flags);
        this.trackIndicesUsed.add(trackIndex);
    }

    public void stop() {
        this.stopped = true;
    }

    public void release() {
        this.released = true;
        if (this.out != null) {
            try {
                this.out.close();
            } catch (IOException ignored) {
                // Test fake.
            }
            this.out = null;
        }
    }

    /** Returns the size of the produced file. */
    public int getBytesWritten() {
        return this.bytesWritten;
    }
}