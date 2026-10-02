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

/**
 * Memory layout of the raw pixels handed to a {@link VideoEncoder}.
 *
 * <p>These describe byte order rather than integer packing, because a renderer reading back from
 * OpenGL produces bytes while a renderer working with {@code int[]} pixels produces integers. The two
 * most common sources are deliberately distinguished, since they disagree:</p>
 *
 * <ul>
 *   <li>{@link #ARGB8888} is what {@code java.awt.BufferedImage.TYPE_INT_ARGB} and Android's
 *       {@code Bitmap.Config.ARGB_8888} hand out as {@code int[]}. Packed as {@code 0xAARRGGBB}, which
 *       in memory on a little-endian machine means the bytes appear as B, G, R, A.</li>
 *   <li>{@link #RGBA} is what {@code glReadPixels} with {@code GL_RGBA}/{@code GL_UNSIGNED_BYTE}
 *       produces, and the bytes appear in R, G, B, A order.</li>
 * </ul>
 *
 * <p>Alpha is ignored during encoding because H.264 4:2:0 has no alpha channel.</p>
 */
public enum PixelFormat {

    /**
     * Packed {@code 0xAARRGGBB} integers. In memory the bytes are B, G, R, A on little-endian
     * hardware. As produced by {@code BufferedImage.TYPE_INT_ARGB} and Android's
     * {@code ARGB_8888}.
     */
    ARGB8888(4),

    /**
     * Straight R, G, B, A bytes. As produced by {@code glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)}.
     */
    RGBA(4),

    /**
     * Packed {@code 0xFFRRGGBB} integers, alpha implicitly opaque. In memory the bytes are B, G, R.
     */
    RGB888(4),

    /**
     * R, G, B bytes with no alpha channel.
     */
    RGB24(3),

    /**
     * B, G, R bytes with no alpha channel. This is the native order of most image and video
     * libraries, so it usually avoids a per-pixel byte swap.
     */
    BGR24(3),

    /**
     * Planar Y, U, V as used by H.264 4:2:0, which needs even width and even height. Not yet
     * accepted by the built-in encoder.
     */
    YUV420(3);

    private final int bytesPerPixel;

    PixelFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    /**
     * Returns the number of bytes each pixel occupies in this format.
     *
     * <p>For {@link #YUV420} this is the average; the real layout is one full-resolution Y plane
     * followed by two half-resolution chroma planes.</p>
     *
     * @return Bytes per pixel
     */
    public int getBytesPerPixel() {
        return this.bytesPerPixel;
    }

    /**
     * Returns whether this format carries an alpha channel.
     *
     * @return Whether there is an alpha channel
     */
    public boolean hasAlpha() {
        return this == ARGB8888 || this == RGBA;
    }

    /**
     * Returns whether this format needs an explicit conversion before encoding, rather than being
     * usable as-is.
     *
     * @return Whether the built-in encoder must reorder this format
     */
    public boolean needsConversion() {
        return this == RGBA || this == RGB24;
    }

    /**
     * Returns the exact number of bytes a tightly packed frame of this format occupies.
     *
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @return The required byte count
     */
    public long sizeOf(int width, int height) {
        if (this == YUV420) {
            long luma = (long) width * (long) height;
            long chroma = luma / 4;
            return luma + 2 * chroma;
        }
        return (long) width * (long) height * this.bytesPerPixel;
    }
}