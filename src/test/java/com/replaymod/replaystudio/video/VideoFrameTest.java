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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VideoFrameTest {

    private static byte[] sized(int length) {
        return new byte[length];
    }

    @Test
    public void accessors() {
        byte[] data = sized(2 * 3 * 4);
        VideoFrame frame = new VideoFrame(2, 3, PixelFormat.RGBA, data, 1234L);

        assertEquals(2, frame.getWidth());
        assertEquals(3, frame.getHeight());
        assertEquals(PixelFormat.RGBA, frame.getFormat());
        assertEquals(1234L, frame.getPresentationTimeMicros());
        assertSame(data, frame.getData());
    }

    @Test
    public void sizeOfMatchesEveryFormat() {
        for (PixelFormat format : PixelFormat.values()) {
            int width = 8, height = 4;
            VideoFrame frame = new VideoFrame(width, height, format, sized((int) format.sizeOf(width, height)), 0L);
            assertEquals(format.name(), (int) format.sizeOf(width, height), frame.getData().length);
        }
    }

    @Test
    public void yuv420SizeIsOneAndAHalfBytesPerPixel() {
        // 8x4 is 32 luma bytes plus two 4x2 chroma planes of 8 bytes each.
        assertEquals(32 + 8 + 8, PixelFormat.YUV420.sizeOf(8, 4));
    }

    @Test
    public void rejectsWrongDataLength() {
        try {
            new VideoFrame(2, 2, PixelFormat.RGB24, sized(11), 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Expected 12 bytes"));
        }
    }

    @Test
    public void rejectsNonPositiveDimensions() {
        try {
            new VideoFrame(0, 2, PixelFormat.RGB24, sized(0), 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new VideoFrame(2, -1, PixelFormat.RGB24, sized(0), 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNulls() {
        try {
            new VideoFrame(2, 2, null, sized(12), 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new VideoFrame(2, 2, PixelFormat.RGB24, null, 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void copyOfDoesNotAliasInput() {
        byte[] data = sized(12);
        VideoFrame frame = VideoFrame.copyOf(2, 2, PixelFormat.RGB24, data, 5L);
        assertNotSame(data, frame.getData());

        data[0] = 99;
        assertEquals(0, frame.getData()[0]);
    }

    @Test
    public void copyIsIndependent() {
        VideoFrame frame = new VideoFrame(2, 2, PixelFormat.RGB24, sized(12), 5L);
        VideoFrame copy = frame.copy();
        assertNotSame(frame.getData(), copy.getData());
        assertEquals(5L, copy.getPresentationTimeMicros());
    }

    @Test
    public void fromArgb8888UsesMemoryByteOrder() {
        // 0xAARRGGBB packed into little-endian memory is B, G, R, A. The encoder reads R, G, B
        // from this buffer, so the blue byte must come first.
        int[] argb = {0xFF804020};
        VideoFrame frame = VideoFrame.fromArgb8888(1, 1, argb, 0L);
        byte[] data = frame.getData();

        assertEquals(4, data.length);
        assertEquals("blue", 0x20, data[0] & 0xFF);
        assertEquals("green", 0x40, data[1] & 0xFF);
        assertEquals("red", 0x80, data[2] & 0xFF);
        assertEquals("alpha", 0xFF, data[3] & 0xFF);
    }

    @Test
    public void fromArgb8888ValidatesLength() {
        try {
            VideoFrame.fromArgb8888(2, 2, new int[3], 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Expected 16 bytes"));
        }
    }

    @Test
    public void pixelFormatFlags() {
        assertTrue(PixelFormat.ARGB8888.hasAlpha());
        assertTrue(PixelFormat.RGBA.hasAlpha());
        assertTrue(!PixelFormat.RGB24.hasAlpha());
        assertTrue(PixelFormat.RGB24.needsConversion());
        assertTrue(PixelFormat.RGBA.needsConversion());
        assertTrue(!PixelFormat.ARGB8888.needsConversion());
        assertEquals(4, PixelFormat.RGB888.getBytesPerPixel());
        assertEquals(3, PixelFormat.BGR24.getBytesPerPixel());
    }
}