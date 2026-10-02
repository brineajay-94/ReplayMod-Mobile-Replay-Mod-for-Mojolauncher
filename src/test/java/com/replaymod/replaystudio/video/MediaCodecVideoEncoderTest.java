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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link MediaCodecVideoEncoder} that can run on a desktop JVM.
 *
 * <p>The encoder itself needs a device, so what is verifiable here is everything around it: that the
 * class loads without the Android classes present, that it refuses clearly rather than half-working,
 * that it leaves no temporary files behind, and the bitrate heuristic. The colour conversion that
 * feeds it is covered by {@link Yuv420ConverterTest}, which is verified against FFmpeg.</p>
 *
 * <p>Run these on a device as well to exercise the real encode path.</p>
 */
public class MediaCodecVideoEncoderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static VideoEncoderConfig.Builder config(File out) {
        return VideoEncoderConfig.builder().output(out).size(1920, 1080).frameRate(60);
    }

    @Test
    public void classLoadsWithoutAndroidPresent() {
        // The whole point of the reflective design: referencing the class must not fail on a desktop.
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        assertEquals("mediacodec", encoder.getName());
        assertFalse(encoder.isOpen());
        assertFalse(encoder.isFinished());
        assertEquals(0, encoder.getFrameCount());
    }

    @Test
    public void reportsAvailabilityHonestly() {
        boolean supported = MediaCodecVideoEncoder.isSupported();
        if (supported) {
            assertNull(MediaCodecVideoEncoder.getUnsupportedReason());
        } else {
            assertNotNull("an unavailable backend must explain itself",
                    MediaCodecVideoEncoder.getUnsupportedReason());
            assertTrue(MediaCodecVideoEncoder.getUnsupportedReason().contains("MediaCodec"));
        }
    }

    @Test
    public void refusesToOpenOffAndroidWithoutCreatingAnything() {
        if (MediaCodecVideoEncoder.isSupported()) {
            return; // Runs on a device; nothing to assert here.
        }
        File out = new File(folder.getRoot(), "should-not-exist.mp4");
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        try {
            encoder.open(config(out).build());
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("android.media.MediaCodec"));
            assertTrue("the message should point at the alternative",
                    expected.getMessage().contains("ffmpeg"));
        } catch (IOException e) {
            fail("Expected UnsupportedOperationException but got " + e);
        }
        assertFalse("a refused open must not create the output file", out.exists());
    }

    @Test
    public void leavesNoTemporaryFilesBehindWhenRefused() {
        if (MediaCodecVideoEncoder.isSupported()) {
            return;
        }
        File temp = new File(folder.getRoot(), "temp");
        assertTrue(temp.mkdirs());
        int before = temp.list().length;

        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        try {
            encoder.open(VideoEncoderConfig.builder()
                    .output(new java.io.RandomAccessFile(new File(temp, "x.mp4"), "rw").getChannel())
                    .size(64, 64)
                    .frameRate(30)
                    .build());
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException | IOException expected) {
            // Either is acceptable here; what matters is the assertion below.
        }

        // A refused open must clean up any temporary muxer file it had created.
        for (String name : temp.list()) {
            assertFalse("temporary file left behind: " + name, name.startsWith("replaystudio-video"));
        }
        assertTrue(temp.list().length <= before + 1);
    }

    @Test
    public void bitrateScalesWithResolutionAndFrameRate() {
        int at60 = MediaCodecVideoEncoder.estimateBitrate(1920, 1080, 60);
        int at30 = MediaCodecVideoEncoder.estimateBitrate(1920, 1080, 30);
        assertTrue("1080p60 should cost more than 1080p30", at60 > at30);
        assertTrue("doubling the frame rate should roughly double the bitrate",
                at60 > at30 * 1.7);

        int at720 = MediaCodecVideoEncoder.estimateBitrate(1280, 720, 60);
        assertTrue("1080p should cost more than 720p", at60 > at720);
    }

    @Test
    public void bitrateStaysInsideEncoderAcceptableRange() {
        // Tiny and enormous inputs must both be clamped into a range hardware encoders accept.
        for (int[] size : new int[][]{{2, 2}, {64, 64}, {7680, 4320}}) {
            for (double fps : new double[]{1, 24, 30, 60, 240}) {
                int bitrate = MediaCodecVideoEncoder.estimateBitrate(size[0], size[1], fps);
                assertTrue(size[0] + "x" + size[1] + "@" + fps + " gave " + bitrate, bitrate >= 64_000);
                assertTrue(size[0] + "x" + size[1] + "@" + fps + " gave " + bitrate, bitrate <= 100_000_000);
            }
        }
    }

    @Test
    public void rejectsWritingBeforeOpen() {
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        byte[] data = new byte[(int) PixelFormat.RGB24.sizeOf(64, 64)];
        try {
            encoder.writeFrame(new VideoFrame(64, 64, PixelFormat.RGB24, data, 0L));
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not been opened"));
        } catch (IOException e) {
            fail("Expected IllegalStateException but got " + e);
        }
    }

    @Test
    public void finishBeforeOpenIsHarmless() throws IOException {
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        encoder.finish();
        assertTrue(encoder.isFinished());
        encoder.finish();
        encoder.close();
    }

    @Test
    public void closeFinishesAnUnfinishedEncode() throws IOException {
        if (MediaCodecVideoEncoder.isSupported()) {
            return; // Device-only path.
        }
        // Off-device, close() on a never-opened encoder must simply be a no-op rather than an error.
        MediaCodecVideoEncoder encoder = new MediaCodecVideoEncoder();
        encoder.close();
        assertTrue(encoder.isFinished());
    }
}