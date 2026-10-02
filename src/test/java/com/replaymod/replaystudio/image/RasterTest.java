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

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RasterTest {

    @Test
    public void dimensionsAndAccessors() {
        Raster raster = new Raster(4, 3);
        assertEquals(4, raster.getWidth());
        assertEquals(3, raster.getHeight());
        assertEquals(12, raster.size());
        assertEquals(12, raster.getPixels().length);
    }

    @Test
    public void newRasterIsTransparentBlack() {
        Raster raster = new Raster(2, 2);
        for (int pixel : raster.getPixels()) {
            assertEquals(0, pixel);
        }
    }

    @Test
    public void pixelRoundTrip() {
        Raster raster = new Raster(3, 2);
        raster.setPixel(2, 1, 0xFF804020);
        assertEquals(0xFF804020, raster.getPixel(2, 1));
        assertEquals(0, raster.getPixel(1, 1));
    }

    @Test
    public void getPixelsIsRowMajor() {
        int[] pixels = new int[6];
        Raster raster = new Raster(3, 2, pixels);
        raster.setPixel(0, 1, 0xAABBCCDD);
        // Row-major means (x, y) lives at index y * width + x, so (0, 1) is index 3 of 6.
        assertEquals(0xAABBCCDD, raster.getPixels()[3]);
        assertEquals(0xAABBCCDD, pixels[3]);
    }

    @Test
    public void copyIsIndependent() {
        Raster original = new Raster(2, 2, new int[]{1, 2, 3, 4});
        Raster copy = original.copy();

        assertNotSame(original.getPixels(), copy.getPixels());
        assertArrayEquals(original.getPixels(), copy.getPixels());

        copy.setPixel(0, 0, 99);
        assertEquals(1, original.getPixel(0, 0));
    }

    @Test
    public void copyOfDoesNotAliasInput() {
        int[] pixels = {1, 2, 3, 4};
        Raster raster = Raster.copyOf(2, 2, pixels);

        assertNotSame(pixels, raster.getPixels());
        assertArrayEquals(pixels, raster.getPixels());
    }

    @Test
    public void sameSizeAs() {
        assertTrue(new Raster(2, 3).sameSizeAs(new Raster(2, 3)));
        assertEquals(false, new Raster(2, 3).sameSizeAs(new Raster(3, 2)));
        assertEquals(false, new Raster(2, 3).sameSizeAs(null));
    }

    @Test
    public void rejectsWrongPixelCount() {
        try {
            new Raster(2, 2, new int[3]);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Expected 4 pixels"));
        }
    }

    @Test
    public void rejectsNonPositiveDimensions() {
        for (int[] size : new int[][]{{0, 4}, {4, 0}, {-1, 4}, {4, -1}}) {
            try {
                new Raster(size[0], size[1]);
                fail("Expected IllegalArgumentException for " + size[0] + "x" + size[1]);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void rejectsNullPixels() {
        try {
            new Raster(2, 2, null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsOutOfBoundsAccess() {
        Raster raster = new Raster(2, 2);
        for (int[] pos : new int[][]{{2, 0}, {-1, 0}, {0, 2}, {0, -1}}) {
            try {
                raster.getPixel(pos[0], pos[1]);
                fail("Expected IndexOutOfBoundsException for " + Arrays.toString(pos));
            } catch (IndexOutOfBoundsException expected) {
                // expected
            }
        }
    }

    @Test
    public void rejectsOverflowingDimensions() {
        try {
            new Raster(Integer.MAX_VALUE, 3);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void constructorKeepsCallerArray() {
        int[] pixels = {1, 2, 3, 4};
        assertSame(pixels, new Raster(2, 2, pixels).getPixels());
    }
}