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

/**
 * A rectangular block of pixels in {@code ARGB_8888} format.
 *
 * <p>This is the platform-neutral image representation used throughout ReplayStudio in place of
 * {@code java.awt.image.BufferedImage}, which does not exist on Android. Pixels are stored in a
 * single row-major {@code int[]} with no row padding, and each pixel packs alpha, red, green and
 * blue in that order from the most significant byte to the least significant one, matching the
 * usual {@code 0xAARRGGBB} layout.</p>
 */
public class Raster {

    private final int width, height;
    private final int[] pixels;

    /**
     * Creates a raster of the given size filled with transparent black pixels.
     *
     * @param width Width in pixels, must be positive
     * @param height Height in pixels, must be positive
     */
    public Raster(int width, int height) {
        this(width, height, new int[checkedSize(width, height)]);
    }

    /**
     * Creates a raster backed by the given pixel array.
     *
     * <p>The array is used as-is, not copied, so callers that intend to reuse or recycle the buffer
     * must not do so until the raster is no longer referenced.</p>
     *
     * @param width Width in pixels, must be positive
     * @param height Height in pixels, must be positive
     * @param pixels Pixels in row-major order, must be exactly {@code width * height} long
     */
    public Raster(int width, int height, int[] pixels) {
        if (pixels == null) throw new IllegalArgumentException("Pixels must not be null");
        int expected = checkedSize(width, height);
        if (pixels.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " pixels for a "
                    + width + "x" + height + " raster but got " + pixels.length);
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * Creates a raster backed by a copy of the given pixel array, leaving the caller's array free
     * for immediate reuse.
     *
     * @param width Width in pixels, must be positive
     * @param height Height in pixels, must be positive
     * @param pixels Pixels in row-major order, must be exactly {@code width * height} long
     * @return The new raster
     */
    public static Raster copyOf(int width, int height, int[] pixels) {
        int size = checkedSize(width, height);
        if (pixels == null) throw new IllegalArgumentException("Pixels must not be null");
        if (pixels.length != size) {
            throw new IllegalArgumentException("Expected " + size + " pixels for a "
                    + width + "x" + height + " raster but got " + pixels.length);
        }
        int[] copy = new int[size];
        System.arraycopy(pixels, 0, copy, 0, size);
        return new Raster(width, height, copy);
    }

    /**
     * Validates the given dimensions and computes the matching pixel count.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     * @return {@code width * height}
     */
    private static int checkedSize(int width, int height) {
        if (width <= 0) throw new IllegalArgumentException("Width must be positive but was " + width);
        if (height <= 0) throw new IllegalArgumentException("Height must be positive but was " + height);
        long size = (long) width * (long) height;
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Raster of " + width + "x" + height
                    + " is too large to address as an int[]");
        }
        return (int) size;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    /**
     * Returns the backing pixel array.
     *
     * <p>The returned array is the raster's own storage, not a copy. Treat it as read-only unless
     * you deliberately own the raster.</p>
     *
     * @return Pixels in row-major order
     */
    public int[] getPixels() {
        return this.pixels;
    }

    /**
     * Returns the number of pixels in this raster.
     *
     * @return {@code width * height}
     */
    public int size() {
        return this.pixels.length;
    }

    /**
     * Returns the pixel at the given coordinates.
     *
     * @param x Column, {@code 0 <= x < width}
     * @param y Row, {@code 0 <= y < height}
     * @return The {@code 0xAARRGGBB} pixel
     */
    public int getPixel(int x, int y) {
        checkBounds(x, y);
        return this.pixels[y * this.width + x];
    }

    /**
     * Sets the pixel at the given coordinates.
     *
     * @param x Column, {@code 0 <= x < width}
     * @param y Row, {@code 0 <= y < height}
     * @param argb The {@code 0xAARRGGBB} pixel
     */
    public void setPixel(int x, int y, int argb) {
        checkBounds(x, y);
        this.pixels[y * this.width + x] = argb;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || x >= this.width) {
            throw new IndexOutOfBoundsException("x=" + x + " outside [0, " + this.width + ")");
        }
        if (y < 0 || y >= this.height) {
            throw new IndexOutOfBoundsException("y=" + y + " outside [0, " + this.height + ")");
        }
    }

    /**
     * Returns an independent copy of this raster.
     *
     * @return The copy
     */
    public Raster copy() {
        return copyOf(this.width, this.height, this.pixels);
    }

    /**
     * Returns whether this raster has the same dimensions as the given one.
     *
     * @param other The other raster
     * @return Whether the dimensions match
     */
    public boolean sameSizeAs(Raster other) {
        return other != null && other.width == this.width && other.height == this.height;
    }

    @Override
    public String toString() {
        return "Raster(" + this.width + "x" + this.height + ")";
    }
}