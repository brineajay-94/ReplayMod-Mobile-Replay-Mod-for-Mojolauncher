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

import android.media.MediaCodec;
import android.media.MediaMuxer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives the real {@link MediaCodecVideoEncoder} state machine against stand-in Android classes.
 *
 * <p>These fakes model the parts of the platform contract that are easy to get wrong: input buffers
 * are only available when free, encoded samples surface asynchronously, the output format is
 * announced exactly once, and end of stream only follows an explicit signal. Everything below runs
 * the production code path, including the reflection, so a mistake in the configure/feed/drain/EOS
 * sequencing fails here rather than on a device.</p>
 *
 * <p>The fakes live in {@code src/test} under the {@code android.media} package so the reflective
 * lookup finds them; nothing here ships.</p>
 */
public class MediaCodecVideoEncoderFakeTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FPS = 30;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ClassLoader originalLoader;

    @Before
    public void useFakes() {
        this.originalLoader = Thread.currentThread().getContextClassLoader();
        // The encoder resolves android.* against the context class loader, and the fakes are on the
        // test class path, so no custom loader is needed. Recording it documents the contract.
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        MediaCodec.lastCreated = null;
        MediaMuxer.all.clear();
    }

    @After
    public void restoreLoader() {
        Thread.currentThread().setContextClassLoader(this.originalLoader);
        MediaCodec.lastCreated = null;
        MediaMuxer.all.clear();
    }

    private static VideoFrame frame(int r, int g, int b, long pts) {
        byte[] data = new byte[(int) PixelFormat.RGB24.sizeOf(WIDTH, HEIGHT)];
        for (int i = 0; i < data.length; i += 3) {
            data[i] = (byte) r;
            data[i + 1] = (byte) g;
            data[i + 2] = (byte) b;
        }
        return new VideoFrame(WIDTH, HEIGHT, PixelFormat.RGB24, data, pts);
    }

    private MediaCodecVideoEncoder open(File out) throws IOException {
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        encoder.open(VideoEncoderConfig.builder()
                .output(out).size(WIDTH, HEIGHT).frameRate(FPS).build());
        return encoder;
    }

    @Test
    public void fakesAreDiscoveredThroughReflection() {
        assertTrue("the stand-in android.media classes must be resolvable",
                MediaCodecVideoEncoder.isSupported());
        assertEquals("mediacodec", new MediaCodecVideoEncoder().getName());
    }

    @Test
    public void configuresTheCodecWithTheRequestedFormat() throws IOException {
        File out = new File(folder.getRoot(), "config.mp4");
        MediaCodecVideoEncoder encoder = open(out);

        MediaCodec codec = MediaCodec.lastCreated;
        assertNotNull("createEncoderByType should have been called", codec);
        assertEquals("video/avc", codec.configuredFormat.mime);
        assertEquals(WIDTH, codec.configuredFormat.getInteger("width"));
        assertEquals(HEIGHT, codec.configuredFormat.getInteger("height"));
        assertEquals(FPS, codec.configuredFormat.getInteger("frame-rate"));
        assertEquals("H.264 needs a color format so the I420 we feed is interpreted",
                0x7F420888, codec.configuredFormat.getInteger("color-format"));
        assertTrue("an encoder bitrate must be requested",
                codec.configuredFormat.getInteger("bitrate") > 0);
        assertEquals("must configure in encode mode", 1, codec.configureFlags);
        assertTrue(codec.started);

        encoder.close();
    }

    @Test
    public void feedsOneI420FramePerCall() throws IOException {
        File out = new File(folder.getRoot(), "feed.mp4");
        MediaCodecVideoEncoder encoder = open(out);

        encoder.writeFrame(frame(220, 20, 20, 0L));
        encoder.writeFrame(frame(20, 220, 20, 33_333L));

        MediaCodec codec = MediaCodec.lastCreated;
        assertEquals(2, codec.inputs.size());
        for (byte[] fed : codec.inputs) {
            assertEquals("each frame must be one full I420 image",
                    WIDTH * HEIGHT * 3 / 2, fed.length);
        }
        // The colour actually handed to the platform must be the converted one, not the RGB input.
        assertEquals("luma of pure red", 85, codec.inputs.get(0)[HEIGHT / 2 * WIDTH + WIDTH / 2] & 0xFF);
        assertEquals(33_333L, (long) codec.inputTimestamps.get(1));

        encoder.close();
    }

    @Test
    public void derivesTimestampsWhenTheFrameDoesNotSupplyThem() throws IOException {
        File out = new File(folder.getRoot(), "pts.mp4");
        MediaCodecVideoEncoder encoder = open(out);

        encoder.writeFrame(frame(10, 10, 10, 0L));
        encoder.writeFrame(frame(10, 10, 10, 0L));
        encoder.writeFrame(frame(10, 10, 10, 0L));
        encoder.close();

        List<Long> timestamps = MediaCodec.lastCreated.inputTimestamps;
        assertEquals(3, timestamps.size());
        assertEquals(0L, (long) timestamps.get(0));
        assertEquals(33_333L, (long) timestamps.get(1));
        assertEquals(66_666L, (long) timestamps.get(2));
    }

    @Test
    public void addsATrackAndMuxesEverySample() throws IOException {
        File out = new File(folder.getRoot(), "mux.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        for (int i = 0; i < 5; i++) {
            encoder.writeFrame(frame(40, 40, 40, i * 1000L));
        }
        encoder.close();

        assertEquals("one muxer should have been created", 1, MediaMuxer.all.size());
        MediaMuxer muxer = MediaMuxer.all.get(0);

        assertTrue("the track must be added exactly once", muxer.tracksAdded.size() == 1);
        assertTrue("the muxer must be started", muxer.started);
        assertTrue("the muxer must be stopped to finalise the file", muxer.stopped);
        assertTrue("the codec must be released", MediaCodec.lastCreated.released);
        assertEquals("end of stream must be signalled exactly once",
                1, MediaCodec.lastCreated.signalEndOfStreamCalls);

        assertEquals("every frame should reach the muxer", 5, muxer.samplesWritten.size());
        assertTrue("the file should contain real data", muxer.getBytesWritten() > 0);
        assertTrue("output should have been produced", out.length() > 0);
    }

    @Test
    public void releasesEveryOutputBuffer() throws IOException {
        File out = new File(folder.getRoot(), "release.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        for (int i = 0; i < 4; i++) {
            encoder.writeFrame(frame(30, 60, 90, i * 1000L));
        }
        encoder.close();

        // Leaking output buffers would eventually starve a real codec.
        List<String> released = MediaCodec.lastCreated.releasedOutputBuffers;
        assertTrue("expected output buffers to be released, got " + released, released.size() >= 4);
    }

    @Test
    public void reportsProgressAndFinalState() throws IOException {
        File out = new File(folder.getRoot(), "state.mp4");
        MediaCodecVideoEncoder encoder = open(out);

        assertTrue(encoder.isOpen());
        assertFalse(encoder.isFinished());
        assertEquals(0, encoder.getFrameCount());

        encoder.writeFrame(frame(1, 2, 3, 0L));
        encoder.writeFrame(frame(1, 2, 3, 1000L));
        assertEquals(2, encoder.getFrameCount());

        encoder.close();
        assertFalse(encoder.isOpen());
        assertTrue(encoder.isFinished());
        assertEquals(2, encoder.getFrameCount());
    }

    @Test
    public void closeIsIdempotent() throws IOException {
        File out = new File(folder.getRoot(), "idempotent.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        encoder.writeFrame(frame(9, 9, 9, 0L));
        encoder.close();

        long size = out.length();
        encoder.close();
        encoder.finish();
        assertEquals("repeat close must not corrupt the output", size, out.length());
        assertEquals("end of stream must still only have been signalled once",
                1, MediaCodec.lastCreated.signalEndOfStreamCalls);
    }

    @Test
    public void handlesAnEmptyEncode() throws IOException {
        File out = new File(folder.getRoot(), "empty.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        encoder.close();
        assertTrue(encoder.isFinished());
        assertEquals(0, encoder.getFrameCount());
    }

    @Test
    public void supportsManyFramesBeyondTheFakeInputPool() throws IOException {
        // The fake only exposes four input buffers, so more frames than that exercises the
        // dequeue/drain interleaving that keeps buffers moving.
        File out = new File(folder.getRoot(), "many.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        for (int i = 0; i < 40; i++) {
            encoder.writeFrame(frame(i, 255 - i, 128, i * 1000L));
        }
        encoder.close();

        assertEquals(40, encoder.getFrameCount());
        assertEquals(40, MediaCodec.lastCreated.inputs.size());
        assertEquals("every frame should have been muxed",
                40, MediaMuxer.all.get(0).samplesWritten.size());
    }

    @Test
    public void writesToAFileChannelByWayOfATemporaryFile() throws IOException {
        File out = new File(folder.getRoot(), "channel.bin");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "rw")) {
            MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
            encoder.open(VideoEncoderConfig.builder()
                    .output(raf.getChannel()).size(WIDTH, HEIGHT).frameRate(FPS).build());
            encoder.writeFrame(frame(5, 5, 200, 0L));
            encoder.close();
        }
        assertTrue("the caller's channel should have received the muxed data", out.length() > 0);

        // The intermediate file MediaMuxer needed must be cleaned up. Check the exact path the muxer
        // was given rather than scanning the temp directory, which may hold files from other runs.
        assertEquals("exactly one muxer should exist", 1, MediaMuxer.all.size());
        File intermediate = new File(MediaMuxer.all.get(0).path);
        assertFalse("temporary muxer file left behind: " + intermediate, intermediate.exists());
    }

    @Test
    public void rejectsAFrameThatDoesNotMatchTheConfiguredSize() throws IOException {
        File out = new File(folder.getRoot(), "mismatch.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        byte[] data = new byte[(int) PixelFormat.RGB24.sizeOf(32, 32)];
        try {
            encoder.writeFrame(new VideoFrame(32, 32, PixelFormat.RGB24, data, 0L));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot change resolution"));
        }
        encoder.close();
    }

    @Test
    public void rejectsYuvInputWhichTheConverterCannotHandle() throws IOException {
        File out = new File(folder.getRoot(), "yuv.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        byte[] data = new byte[(int) PixelFormat.YUV420.sizeOf(WIDTH, HEIGHT)];
        try {
            encoder.writeFrame(new VideoFrame(WIDTH, HEIGHT, PixelFormat.YUV420, data, 0L));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("YUV420"));
        }
        encoder.close();
    }

    @Test
    public void rejectsReopening() throws IOException {
        File out = new File(folder.getRoot(), "reopen.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        try {
            encoder.open(VideoEncoderConfig.builder()
                    .output(out).size(WIDTH, HEIGHT).frameRate(FPS).build());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already been opened"));
        }
        encoder.close();
    }

    @Test
    public void rejectsWritingAfterFinish() throws IOException {
        File out = new File(folder.getRoot(), "after.mp4");
        MediaCodecVideoEncoder encoder = open(out);
        encoder.writeFrame(frame(1, 1, 1, 0L));
        encoder.close();
        try {
            encoder.writeFrame(frame(1, 1, 1, 1000L));
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already been finished"));
        }
    }
}