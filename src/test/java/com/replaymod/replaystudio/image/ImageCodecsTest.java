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
package com.replaymod.replaystudio.image;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link ImageCodecs} and the desktop {@code javax.imageio} implementation.
 *
 * <p>The imageio source set is on the test classpath, so its ServiceLoader registration is present
 * here even though the core jar deliberately does not ship it.</p>
 */
public class ImageCodecsTest {

    @Test
    public void discoversImageIoCodecViaServiceLoader() {
        ImageCodecs.reset();

        List<ImageCodec> discovered = ImageCodecs.getDiscovered();
        assertTrue("expected the imageio source set to register a codec, got " + discovered,
                discovered.size() >= 1);
        assertNotNull("expected an available codec on a desktop JVM", ImageCodecs.getDefault());
        assertTrue(ImageCodecs.isAvailable());
    }

    @Test
    public void looksUpCodecByNameCaseInsensitively() {
        ImageCodecs.reset();
        ImageCodec codec = ImageCodecs.byName("IMAGEIO");
        assertNotNull(codec);
        assertEquals("imageio", codec.getName());
        assertNull(ImageCodecs.byName("does-not-exist"));
        assertNull(ImageCodecs.byName(null));
    }

    @Test
    public void explicitDefaultTakesPrecedence() {
        ImageCodecs.reset();
        try {
            ImageCodec sentinel = new ImageCodec() {
                @Override public String getName() { return "sentinel"; }
                @Override public boolean isAvailable() { return true; }
                @Override public byte[] encodeJpeg(Raster raster, int quality) { return new byte[0]; }
                @Override public Raster decodeJpeg(byte[] data) { return new Raster(1, 1); }
            };

            ImageCodecs.setDefault(sentinel);
            assertSame(sentinel, ImageCodecs.getDefault());
            assertSame(sentinel, ImageCodecs.getExplicitDefault());

            ImageCodecs.setDefault(null);
            assertNull(ImageCodecs.getExplicitDefault());
        } finally {
            ImageCodecs.reset();
        }
    }

    @Test
    public void requireExplainsWhatToInstallWhenMissing() {
        ImageCodecs.reset();
        try {
            ImageCodecs.setDefault(new ImageCodec() {
                @Override public String getName() { return "unavailable"; }
                @Override public boolean isAvailable() { return false; }
                @Override public byte[] encodeJpeg(Raster raster, int quality) { return null; }
                @Override public Raster decodeJpeg(byte[] data) { return null; }
            });
            try {
                ImageCodecs.require();
                fail("Expected IllegalStateException");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("setDefault"));
                assertTrue(expected.getMessage().contains("imageio"));
            }
        } finally {
            ImageCodecs.reset();
        }
    }

    @Test
    public void jpegRoundTripPreservesDimensionsAndRoughlyPreservesPixels() throws IOException {
        ImageCodec codec = ImageCodecs.require();

        // 64x64 so that JPEG's 8x8 block padding cannot crop the edges.
        int width = 64, height = 64;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean checker = ((x / 8) + (y / 8)) % 2 == 0;
                pixels[y * width + x] = checker ? 0xFFFF0000 : 0xFF0000FF;
            }
        }
        Raster original = new Raster(width, height, pixels);

        byte[] jpeg = codec.encodeJpeg(original, 90);
        assertTrue("expected non-empty JPEG", jpeg.length > 0);

        Raster decoded = codec.decodeJpeg(jpeg);
        assertEquals(width, decoded.getWidth());
        assertEquals(height, decoded.getHeight());

        // JPEG is lossy and chroma-subsamples 4:2:0, so sample the centre of 8x8 blocks rather than
        // block corners, where ringing from the neighbouring cell bleeds across.
        // Block (0,0) is red, block (1,0) is blue.
        assertChannel(decoded, 4, 4, 0, 200);
        assertChannel(decoded, 12, 4, 1, 200);
    }

    @Test
    public void jpegBytesLookLikeAJpeg() throws IOException {
        byte[] jpeg = ImageCodecs.require().encodeJpeg(new Raster(16, 16), 80);
        // Every JPEG starts with the SOI marker 0xFF 0xD8.
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
    }

    @Test
    public void alphaIsFlattenedRatherThanBlack() throws IOException {
        // A fully transparent red pixel must not decode as pure black, otherwise transparent areas of
        // a thumbnail would render as opaque black instead of the page background.
        Raster raster = new Raster(32, 32);
        for (int i = 0; i < raster.size(); i++) raster.getPixels()[i] = 0x00FF0000;

        Raster decoded = ImageCodecs.require().decodeJpeg(ImageCodecs.require().encodeJpeg(raster, 90));
        int pixel = decoded.getPixel(16, 16);
        int red = (pixel >> 16) & 0xFF;
        assertTrue("expected red channel to survive alpha flattening but was " + red, red > 200);
    }

    @Test
    public void rejectsOutOfRangeQuality() throws IOException {
        try {
            ImageCodecs.require().encodeJpeg(new Raster(8, 8), 0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ImageCodecs.require().encodeJpeg(new Raster(8, 8), 101);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNonJpegData() throws IOException {
        try {
            ImageCodecs.require().decodeJpeg(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    private static void assertChannel(Raster raster, int x, int y, int channel, int minimum) {
        int pixel = raster.getPixel(x, y);
        int value = channel == 0 ? (pixel >> 16) & 0xFF : (pixel & 0xFF);
        assertTrue("channel " + channel + " at (" + x + "," + y + ") was " + value
                + ", expected at least " + minimum, value >= minimum);
    }
}