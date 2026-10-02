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

import com.replaymod.replaystudio.lib.jcodec.api.FrameGrab;
import com.replaymod.replaystudio.lib.jcodec.api.JCodecException;
import com.replaymod.replaystudio.lib.jcodec.common.io.FileChannelWrapper;
import com.replaymod.replaystudio.lib.jcodec.common.io.NIOUtils;
import com.replaymod.replaystudio.lib.jcodec.common.model.Picture;
import com.replaymod.replaystudio.studio.ReplayStudio;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
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

public class JcodecVideoEncoderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * Opts into the broken-colour backend for the duration of a test.
     *
     * <p>Most of these tests exercise the muxing, framing and format-mapping machinery rather than
     * colour, and that machinery is verified correct. The one test that checks colour is disabled
     * separately; see {@link #redThenGreenProducesDistinctFrames()}.</p>
     */
    @Before
    public void allowBrokenJcodec() {
        System.setProperty(JcodecVideoEncoder.OVERRIDE_PROPERTY, "true");
    }

    @After
    public void disallowBrokenJcodec() {
        System.clearProperty(JcodecVideoEncoder.OVERRIDE_PROPERTY);
    }

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    private VideoEncoderConfig.Builder config(File out) {
        return VideoEncoderConfig.builder().output(out).size(WIDTH, HEIGHT).frameRate(30);
    }

    /** A frame of a single solid colour in the given format. */
    private static VideoFrame solidFrame(PixelFormat format, int r, int g, int b, long pts) {
        byte[] data = new byte[(int) format.sizeOf(WIDTH, HEIGHT)];
        int stride = format.getBytesPerPixel();
        for (int i = 0; i < data.length; i += stride) {
            switch (format) {
                case BGR24:
                    data[i] = (byte) b; data[i + 1] = (byte) g; data[i + 2] = (byte) r;
                    break;
                case RGB24:
                    data[i] = (byte) r; data[i + 1] = (byte) g; data[i + 2] = (byte) b;
                    break;
                case ARGB8888:
                    // memory order is B, G, R, A
                    data[i] = (byte) b; data[i + 1] = (byte) g; data[i + 2] = (byte) r; data[i + 3] = (byte) 0xFF;
                    break;
                case RGBA:
                case RGB888:
                    data[i] = (byte) r; data[i + 1] = (byte) g; data[i + 2] = (byte) b;
                    if (stride == 4) data[i + 3] = (byte) 0xFF;
                    break;
                default:
                    throw new IllegalArgumentException(format.toString());
            }
        }
        return new VideoFrame(WIDTH, HEIGHT, format, data, pts);
    }

/**
 * The jcodec backend must not be discoverable, because it produces the wrong colour and would
 * otherwise be picked up silently. See {@link JcodecVideoEncoder#DEFECT}.
 */
@Test
public void isNotRegisteredBecauseItWouldProduceWrongColour() {
    ReplayStudio studio = new ReplayStudio();
    assertNull("the broken jcodec backend must not be auto-discovered",
            studio.loadVideoEncoder("jcodec"));
    // Lookup itself must still behave: case-insensitive, null-safe, null for unknown names.
    assertNull(studio.loadVideoEncoder("no-such-encoder"));
    assertNull(studio.loadVideoEncoder(null));
}

@Test
public void refusesToOpenWithoutAnExplicitOverride() throws IOException {
    System.clearProperty(JcodecVideoEncoder.OVERRIDE_PROPERTY);
    JcodecVideoEncoder encoder = new JcodecVideoEncoder();
    assertEquals("jcodec", encoder.getName());
    try {
        encoder.open(config(new File(folder.getRoot(), "disabled.mp4")).build());
        fail("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
        assertTrue(expected.getMessage().contains("incorrect colours"));
    }
    assertFalse("no file should be produced by a refused open",
            new File(folder.getRoot(), "disabled.mp4").exists());
}

@Test
public void discoveryPlumbingWorksForAnyRegisteredEncoder() {
    // Verifies loadVideoEncoder itself, independent of which backends happen to be registered.
    ReplayStudio studio = new ReplayStudio() {
        @Override
        public VideoEncoder loadVideoEncoder(String name) {
            return "stub".equalsIgnoreCase(name) ? new JcodecVideoEncoder() : null;
        }
    };
    assertNotNull(studio.loadVideoEncoder("STUB"));
    assertNull(studio.loadVideoEncoder("other"));
}

@Test
public void writesAWellFormedMp4() throws IOException {
        File out = new File(folder.getRoot(), "basic.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        assertTrue(encoder.isOpen());
        assertFalse(encoder.isFinished());

        for (int i = 0; i < 8; i++) {
            encoder.writeFrame(solidFrame(PixelFormat.ARGB8888, 200, 30, 30, i * 1000L));
        }
        encoder.finish();

        assertTrue(encoder.isFinished());
        assertFalse(encoder.isOpen());
        assertEquals(8, encoder.getFrameCount());
        assertTrue("expected a non-empty file", out.length() > 0);

        // Every MP4 starts with a 'ftyp' box: a 4-byte size followed by the four ASCII bytes.
        byte[] head = readHead(out, 8);
        assertEquals("ftyp", new String(head, 4, 4, "UTF-8"));
        assertEquals(8, encoder.getFrameCount());
    }

    @Test
    public void decodedFrameCountMatchesWhatWasWritten() throws IOException {
        File out = new File(folder.getRoot(), "count.mp4");
        int frames = 6;
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).frameRate(30, 1).build());
        for (int i = 0; i < frames; i++) {
            encoder.writeFrame(solidFrame(PixelFormat.RGB24, 40, 200, 60, i * 1000L));
        }
        encoder.finish();

        int decoded = countDecodedFrames(out);
        assertEquals("frame count should survive a mux/demux round trip", frames, decoded);
    }

    @Test
    public void allFormatsProduceIdenticalOutputForTheSameColour() throws IOException {
        // The strongest available check on byte-order handling. Every supported format is asked to
        // describe the exact same opaque colour. If any format's R, G and B were mis-mapped by the
        // repack, the decoded pictures would differ from the RGB24 reference.
        byte[][] reference = null;
        for (PixelFormat format : PixelFormat.values()) {
            if (format == PixelFormat.YUV420) continue; // planar input not supported yet

            File out = new File(folder.getRoot(), "fmt-" + format + ".mp4");
            JcodecVideoEncoder encoder = new JcodecVideoEncoder();
            encoder.open(config(out).build());
            // Opaque, strongly asymmetric colour so any channel swap is visible.
            encoder.writeFrame(solidFrame(format, 200, 30, 30, 0L));
            encoder.writeFrame(solidFrame(format, 200, 30, 30, 1000L));
            encoder.finish();

            Picture decoded = decodeFrame(out, 0);
            assertNotNull(format + " should decode", decoded);
            assertEquals(WIDTH, decoded.getWidth());
            assertEquals(HEIGHT, decoded.getHeight());

            byte[][] planes = decoded.getData();
            if (reference == null) {
                reference = planes;
            } else {
                assertTrue(format + " decoded differently from RGB24: " + describe(planes)
                                + " vs " + describe(reference),
                        java.util.Arrays.deepEquals(reference, planes));
            }
        }
    }

    @Test
    public void decodedLumaFollowsFrameBrightness() throws IOException {
        // H.264 decodes to YUV420, so verify through luma rather than trying to reconstruct RGB.
        File dark = new File(folder.getRoot(), "dark.mp4");
        encodeSolid(dark, 20, 20, 20);
        File bright = new File(folder.getRoot(), "bright.mp4");
        encodeSolid(bright, 240, 240, 240);

        int darkLuma = luma(decodeFrame(dark, 0));
        int brightLuma = luma(decodeFrame(bright, 0));
        assertTrue("dark frame luma " + darkLuma + " should be well below bright frame luma "
                + brightLuma, brightLuma > darkLuma + 100);
    }

    /**
     * Known defect in JCodec 0.2.5, not in this library.
     *
     * <p>JCodec's internal RGB to YUV conversion is wrong. Verified against FFmpeg 8.1.1 as an
     * independent reference:</p>
     *
     * <pre>
     * pure red (220, 20, 20), centre pixel, BT.601 limited range:
     *   FFmpeg encode+decode : Y=85  U=98  V=216
     *   JCodec  0.2.5         : Y=251 U=9   V=228
     * </pre>
     *
     * <p>Luma is far outside even the legal 16-235 range and Cb is inverted, so the resulting MP4
     * decodes to the wrong hue. Confirmed by decoding the produced file with FFmpeg: red comes back
     * cyan, green magenta, blue yellow. The container itself is fine, and per-pixel mapping across
     * input formats is self-consistent (see
     * {@link #allFormatsProduceIdenticalOutputForTheSameColour()}), so only the absolute colour is
     * affected.</p>
     *
     * <p>Ruled out as causes: setting an explicit crop on the {@code Picture} makes no difference,
     * and {@code ColorSpace.BGR} is rejected outright by {@code SequenceEncoder}, which demands
     * {@code ColorSpace.RGB}. JCodec's own decoder also fails to recover chroma here, returning zero
     * for both chroma planes on every colour.</p>
     *
     * <p>Re-enable this once the encoder either converts colour itself before handing frames to
     * JCodec's low-level {@code org.jcodec.codecs.h264.H264Encoder}, or JCodec's
     * {@code RgbToYuv420j} is fixed.</p>
     */
    @Ignore("JCodec 0.2.5 produces the wrong colour; see the javadoc for the measured evidence")
    @Test
    public void redThenGreenProducesDistinctFrames() throws IOException {
        // Guards against the encoder silently dropping frames.
        File out = new File(folder.getRoot(), "motion.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        encoder.writeFrame(solidFrame(PixelFormat.ARGB8888, 220, 20, 20, 0L));
        for (int i = 1; i < 5; i++) {
            encoder.writeFrame(solidFrame(PixelFormat.ARGB8888, 20, 220, 20, i * 1000L));
        }
        encoder.finish();

        // Cr (the V plane) rises with red content, so it separates a red frame from a green one.
        int redCr = chroma(decodeFrame(out, 0), 2);
        int greenCr = chroma(decodeFrame(out, 4), 2);
        assertTrue("red frame should have high Cr but was " + redCr, redCr > 180);
        assertTrue("green frame should have low Cr but was " + greenCr, greenCr < 120);
    }

    private void encodeSolid(File out, int r, int g, int b) throws IOException {
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        encoder.writeFrame(solidFrame(PixelFormat.RGB24, r, g, b, 0L));
        encoder.writeFrame(solidFrame(PixelFormat.RGB24, r, g, b, 1000L));
        encoder.finish();
    }

    @Test
    public void writesToAFileChannelWithoutOwningIt() throws IOException {
        File out = new File(folder.getRoot(), "channel.mp4");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "rw")) {
            VideoEncoderConfig cfg = VideoEncoderConfig.builder()
                    .output(raf.getChannel())
                    .size(WIDTH, HEIGHT)
                    .frameRate(30)
                    .build();
            JcodecVideoEncoder encoder = new JcodecVideoEncoder();
            encoder.open(cfg);
            encoder.writeFrame(solidFrame(PixelFormat.RGB24, 10, 10, 200, 0L));
            encoder.finish();

            assertNull(cfg.getOutputFile());
            assertNotNull(cfg.getOutputChannel());
        }
        assertTrue("the caller's channel should still be usable after close", out.length() > 0);
    }

    @Test
    public void closeFinishesAnUnfinishedEncode() throws IOException {
        File out = new File(folder.getRoot(), "autoclose.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        encoder.writeFrame(solidFrame(PixelFormat.RGB24, 90, 90, 90, 0L));
        encoder.close();

        assertTrue(encoder.isFinished());
        assertTrue("close() must still produce a playable file", out.length() > 0);
    }

    @Test
    public void rejectsWritingBeforeOpen() throws IOException {
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        assertFalse(encoder.isOpen());
        try {
            encoder.writeFrame(solidFrame(PixelFormat.RGB24, 1, 2, 3, 0L));
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not been opened"));
        }
    }

    @Test
    public void rejectsReopening() throws IOException {
        File out = new File(folder.getRoot(), "reopen.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        try {
            encoder.open(config(out).build());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already been opened"));
        }
    }

    @Test
    public void rejectsWritingAfterFinish() throws IOException {
        File out = new File(folder.getRoot(), "after-finish.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        encoder.writeFrame(solidFrame(PixelFormat.RGB24, 1, 2, 3, 0L));
        encoder.finish();
        try {
            encoder.writeFrame(solidFrame(PixelFormat.RGB24, 1, 2, 3, 1000L));
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already been finished"));
        }
    }

    @Test
    public void doubleFinishIsHarmless() throws IOException {
        File out = new File(folder.getRoot(), "double-finish.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        encoder.writeFrame(solidFrame(PixelFormat.RGB24, 1, 2, 3, 0L));
        encoder.finish();
        long afterFirst = out.length();
        encoder.finish();
        encoder.close();
        assertEquals("a second finish must not change the output", afterFirst, out.length());
    }

    @Test
    public void rejectsMismatchedFrameSize() throws IOException {
        File out = new File(folder.getRoot(), "mismatch.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        try {
            byte[] data = new byte[(int) PixelFormat.RGB24.sizeOf(32, 32)];
            encoder.writeFrame(new VideoFrame(32, 32, PixelFormat.RGB24, data, 0L));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot change resolution"));
        }
    }

    @Test
    public void rejectsYuv420Input() throws IOException {
        File out = new File(folder.getRoot(), "yuv.mp4");
        JcodecVideoEncoder encoder = new JcodecVideoEncoder();
        encoder.open(config(out).build());
        try {
            byte[] data = new byte[(int) PixelFormat.YUV420.sizeOf(WIDTH, HEIGHT)];
            encoder.writeFrame(new VideoFrame(WIDTH, HEIGHT, PixelFormat.YUV420, data, 0L));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("YUV420"));
        }
    }

    @Test
    public void configRejectsOddDimensionsBeforeAnyFileIsTouched() {
        try {
            VideoEncoderConfig.builder()
                    .output(new File(folder.getRoot(), "odd.mp4"))
                    .size(65, 48)
                    .build();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("even"));
        }
    }

    /**
     * Samples the centre pixel of a decoded picture.
     *
     * <p>H.264 decodes to YUV420. JCodec stores all three planes at full frame size rather than
     * subsampling the chroma planes in memory, so plane 0 is luma, plane 1 is Cb and plane 2 is Cr,
     * each addressed as {@code y * width + x}.</p>
     */
    private static int plane(Picture picture, int index) {
        byte[][] planes = picture.getData();
        int at = (picture.getHeight() / 2) * picture.getWidth() + (picture.getWidth() / 2);
        return planes[index][at] & 0xFF;
    }

    private static int luma(Picture picture) {
        return plane(picture, 0);
    }

    private static int chroma(Picture picture, int index) {
        return plane(picture, index);
    }

    private static String describe(byte[][] planes) {
        return "centre=[" + (planes[0].length / 2 & 0xFF) + "]";
    }

    private static byte[] readHead(File file, int length) throws IOException {
        byte[] buffer = new byte[length];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(buffer);
            if (read != length) throw new IOException("File shorter than " + length + " bytes");
        }
        return buffer;
    }

    private static Picture decodeFrame(File file, int frameNumber) throws IOException {
        FileChannelWrapper channel = NIOUtils.readableChannel(file);
        try {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            grab.seekToFramePrecise(frameNumber);
            return grab.getNativeFrame();
        } catch (JCodecException e) {
            throw new IOException("Could not decode " + file + ": " + e.getMessage(), e);
        } finally {
            channel.close();
        }
    }

    private static int countDecodedFrames(File file) throws IOException {
        FileChannelWrapper channel = NIOUtils.readableChannel(file);
        try {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            int count = 0;
            while (grab.getNativeFrame() != null) {
                count++;
            }
            return count;
        } catch (JCodecException e) {
            throw new IOException("Could not decode " + file + ": " + e.getMessage(), e);
        } finally {
            channel.close();
        }
    }
}