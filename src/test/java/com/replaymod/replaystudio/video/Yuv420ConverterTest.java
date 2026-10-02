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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link Yuv420Converter}.
 *
 * <p>The expected values were produced by FFmpeg 8.1.1 for the same flat colours, so these tests
 * assert agreement with a trusted reference implementation rather than with hand-picked constants.</p>
 */
public class Yuv420ConverterTest {

    /** Centre pixel of a 4x4 crop at (30, 22) of a 64x48 frame, as FFmpeg reports it. */
    private static int[] convertSolid(int width, int height, PixelFormat format, int r, int g, int b) {
        byte[] data = new byte[(int) format.sizeOf(width, height)];
        int stride = format.getBytesPerPixel();
        for (int i = 0; i < data.length; i += stride) {
            switch (format) {
                case BGR24:
                    data[i] = (byte) b; data[i + 1] = (byte) g; data[i + 2] = (byte) r;
                    break;
                case ARGB8888:
                    data[i] = (byte) b; data[i + 1] = (byte) g; data[i + 2] = (byte) r; data[i + 3] = (byte) 0xFF;
                    break;
                default:
                    data[i] = (byte) r; data[i + 1] = (byte) g; data[i + 2] = (byte) b;
                    if (stride == 4) data[i + 3] = (byte) 0xFF;
                    break;
            }
        }
        VideoFrame frame = new VideoFrame(width, height, format, data, 0L);
        byte[] i420 = new Yuv420Converter().toI420(frame);

        int lumaSize = width * height;
        int chromaWidth = width / 2, chromaHeight = height / 2;
        int chromaSize = chromaWidth * chromaHeight;
        // Centre block of the chroma plane, which is half the frame in each direction.
        int index = (chromaHeight / 2) * chromaWidth + (chromaWidth / 2);
        return new int[]{
                i420[(height / 2) * width + (width / 2)] & 0xFF,
                i420[lumaSize + index] & 0xFF,
                i420[lumaSize + chromaSize + index] & 0xFF
        };
    }

    @Test
    public void matchesFfmpegForRed() {
        // FFmpeg: Y=85 U=98 V=216
        int[] yuv = convertSolid(64, 48, PixelFormat.RGB24, 220, 20, 20);
        assertEquals("Y", 85, yuv[0]);
        assertEquals("U", 98, yuv[1]);
        assertEquals("V", 216, yuv[2]);
    }

    @Test
    public void matchesFfmpegForGreen() {
        int[] yuv = convertSolid(64, 48, PixelFormat.RGB24, 20, 220, 20);
        assertEquals("Y", 134, yuv[0]);
        assertEquals("U", 70, yuv[1]);
        assertEquals("V", 54, yuv[2]);
    }

    @Test
    public void matchesFfmpegForBlue() {
        // FFmpeg: Y=53 U=216 V=114
        int[] yuv = convertSolid(64, 48, PixelFormat.RGB24, 20, 20, 220);
        assertEquals("Y", 53, yuv[0]);
        assertEquals("U", 216, yuv[1]);
        assertEquals("V", 114, yuv[2]);
    }

    @Test
    public void matchesFfmpegForMidGrey() {
        int[] yuv = convertSolid(64, 48, PixelFormat.RGB24, 128, 128, 128);
        assertEquals("Y", 126, yuv[0]);
        assertEquals("grey has no colour, so chroma sits at the midpoint", 128, yuv[1]);
        assertEquals(128, yuv[2]);
    }

    @Test
    public void everySupportedFormatAgreesOnTheSameColour() {
        // Guards the byte-order handling: a swap in any one format would show up here.
        int[] reference = convertSolid(32, 32, PixelFormat.RGB24, 220, 20, 20);
        for (PixelFormat format : new PixelFormat[]{
                PixelFormat.ARGB8888, PixelFormat.RGBA, PixelFormat.RGB888,
                PixelFormat.RGB24, PixelFormat.BGR24}) {
            int[] yuv = convertSolid(32, 32, format, 220, 20, 20);
            assertEquals(format + " Y", reference[0], yuv[0]);
            assertEquals(format + " U", reference[1], yuv[1]);
            assertEquals(format + " V", reference[2], yuv[2]);
        }
    }

    @Test
    public void outputSizeIsOneAndAHalfBytesPerPixel() {
        for (int[] size : new int[][]{{64, 48}, {16, 16}, {2, 2}, {1920, 1080}}) {
            int width = size[0], height = size[1];
            byte[] data = new byte[(int) PixelFormat.RGB24.sizeOf(width, height)];
            byte[] i420 = new Yuv420Converter().toI420(
                    new VideoFrame(width, height, PixelFormat.RGB24, data, 0L));
            assertEquals(width * height * 3 / 2, i420.length);
        }
    }

    @Test
    public void lumaStaysInsideLimitedRangeForPureBlackAndWhite() {
        // Limited range must never emit 0 or 255, otherwise players that honour the range render
        // crushed blacks and blown highlights.
        for (int[] colour : new int[][]{{0, 0, 0}, {255, 255, 255}}) {
            assertTrue("black luma " + Yuv420Converter.luma(colour[0], colour[1], colour[2]),
                    Yuv420Converter.luma(colour[0], colour[1], colour[2]) >= 16);
            assertTrue("white luma " + Yuv420Converter.luma(colour[0], colour[1], colour[2]),
                    Yuv420Converter.luma(colour[0], colour[1], colour[2]) <= 235);
        }
        assertEquals(16, Yuv420Converter.luma(0, 0, 0));
        assertEquals(235, Yuv420Converter.luma(255, 255, 255));
    }

    @Test
    public void chromaStaysInsideLimitedRange() {
        for (int r = 0; r <= 255; r += 51) {
            for (int g = 0; g <= 255; g += 51) {
                for (int b = 0; b <= 255; b += 51) {
                    int cb = Yuv420Converter.chromaB(r, g, b);
                    int cr = Yuv420Converter.chromaR(r, g, b);
                    assertTrue("Cb " + cb, cb >= 16 && cb <= 240);
                    assertTrue("Cr " + cr, cr >= 16 && cr <= 240);
                }
            }
        }
    }

    @Test
    public void rejectsAlreadyYuvInput() {
        byte[] data = new byte[(int) PixelFormat.YUV420.sizeOf(16, 16)];
        try {
            new Yuv420Converter().toI420(new VideoFrame(16, 16, PixelFormat.YUV420, data, 0L));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("already YUV420"));
        }
    }

    @Test
    public void convertsAGradientWithoutBlowingUp() {
        // A left-to-right ramp exercises the per-pixel path rather than a flat colour.
        int width = 8, height = 8;
        byte[] data = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = x * 255 / (width - 1);
                int at = (y * width + x) * 3;
                data[at] = (byte) v;
                data[at + 1] = (byte) v;
                data[at + 2] = (byte) v;
            }
        }
        byte[] i420 = new Yuv420Converter().toI420(
                new VideoFrame(width, height, PixelFormat.RGB24, data, 0L));

        assertEquals(width * height * 3 / 2, i420.length);
        // Chroma of a grey ramp must stay at the neutral midpoint.
        int chromaSize = (width / 2) * (height / 2);
        for (int i = 0; i < chromaSize; i++) {
            int cb = i420[width * height + i] & 0xFF;
            int cr = i420[width * height + chromaSize + i] & 0xFF;
            assertTrue("grey ramp should be neutral, Cb=" + cb, Math.abs(cb - 128) <= 1);
            assertTrue("grey ramp should be neutral, Cr=" + cr, Math.abs(cr - 128) <= 1);
        }
        // Luma must increase left to right.
        for (int y = 0; y < height; y++) {
            int left = i420[y * width] & 0xFF;
            int right = i420[y * width + width - 1] & 0xFF;
            assertTrue("luma should ramp, left=" + left + " right=" + right, right > left);
        }
    }
}