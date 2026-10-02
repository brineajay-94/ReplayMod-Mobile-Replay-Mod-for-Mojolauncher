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
 * A single raw, uncompressed video frame destined for a {@link VideoEncoder}.
 *
 * <p>Buffer ownership is explicit and follows the same discipline as {@code Packet} elsewhere in this
 * library. The array passed to the constructor is used as-is and never copied, so:</p>
 *
 * <ul>
 *   <li>the encoder must not retain the array after {@link VideoEncoder#writeFrame(VideoFrame)}
 *       returns, which lets a renderer recycle its readback buffer immediately;</li>
 *   <li>a renderer that wants to hand over ownership of a buffer it will never touch again can do so
 *       directly, with no copy.</li>
 * </ul>
 *
 * <p>If you need to keep the pixels after encoding, pass {@link #copyOf} instead.</p>
 */
public class VideoFrame {

    private final int width, height;
    private final PixelFormat format;
    private final byte[] data;
    private final long presentationTimeMicros;

    /**
     * Creates a frame backed by the given array.
     *
     * @param width Frame width in pixels, must be positive
     * @param height Frame height in pixels, must be positive
     * @param format Layout of {@code data}, must not be {@code null}
     * @param data Tightly packed pixels, must be exactly {@code format.sizeOf(width, height)} long
     * @param presentationTimeMicros Presentation timestamp in microseconds, used for ordering
     */
    public VideoFrame(int width, int height, PixelFormat format, byte[] data, long presentationTimeMicros) {
        if (format == null) throw new IllegalArgumentException("PixelFormat must not be null");
        if (data == null) throw new IllegalArgumentException("Data must not be null");
        if (width <= 0) throw new IllegalArgumentException("Width must be positive but was " + width);
        if (height <= 0) throw new IllegalArgumentException("Height must be positive but was " + height);
        long expected = format.sizeOf(width, height);
        if (data.length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " bytes for a " + width + "x" + height
                    + " " + format + " frame but got " + data.length);
        }
        this.width = width;
        this.height = height;
        this.format = format;
        this.data = data;
        this.presentationTimeMicros = presentationTimeMicros;
    }

    /**
     * Creates a frame backed by a copy of the given array, leaving the caller's array free for
     * immediate reuse.
     *
     * @param width Frame width in pixels, must be positive
     * @param height Frame height in pixels, must be positive
     * @param format Layout of {@code data}, must not be {@code null}
     * @param data Tightly packed pixels, must be exactly {@code format.sizeOf(width, height)} long
     * @param presentationTimeMicros Presentation timestamp in microseconds
     * @return The new frame
     */
    public static VideoFrame copyOf(int width, int height, PixelFormat format, byte[] data,
                                    long presentationTimeMicros) {
        if (data == null) throw new IllegalArgumentException("Data must not be null");
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return new VideoFrame(width, height, format, copy, presentationTimeMicros);
    }

    /**
     * Creates a frame from packed ARGB pixels, as produced by {@code BufferedImage.TYPE_INT_ARGB} or
     * Android's {@code ARGB_8888}.
     *
     * <p>The array is reinterpreted as bytes in memory order rather than copied per pixel, so this
     * does not work on big-endian hardware; use {@link #copyOf} there. All mainstream targets, Android
     * included, are little-endian.</p>
     *
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param argb Packed {@code 0xAARRGGBB} pixels in row-major order
     * @param presentationTimeMicros Presentation timestamp in microseconds
     * @return The new frame
     */
    public static VideoFrame fromArgb8888(int width, int height, int[] argb, long presentationTimeMicros) {
        if (argb == null) throw new IllegalArgumentException("Pixels must not be null");
        byte[] bytes = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            bytes[i * 4] = (byte) (pixel & 0xFF);        // blue
            bytes[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF);  // green
            bytes[i * 4 + 2] = (byte) ((pixel >> 16) & 0xFF); // red
            bytes[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF); // alpha
        }
        return new VideoFrame(width, height, PixelFormat.ARGB8888, bytes, presentationTimeMicros);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public PixelFormat getFormat() {
        return this.format;
    }

    /**
     * Returns the backing pixel array.
     *
     * <p>This is the frame's own storage, not a copy, and is only valid for the duration of the
     * {@link VideoEncoder#writeFrame(VideoFrame)} call it was passed to.</p>
     *
     * @return Tightly packed pixels
     */
    public byte[] getData() {
        return this.data;
    }

    public long getPresentationTimeMicros() {
        return this.presentationTimeMicros;
    }

    /**
     * Returns an independent copy of this frame.
     *
     * @return The copy
     */
    public VideoFrame copy() {
        return copyOf(this.width, this.height, this.format, this.data, this.presentationTimeMicros);
    }

    @Override
    public String toString() {
        return "VideoFrame(" + this.width + "x" + this.height + " " + this.format
                + " pts=" + this.presentationTimeMicros + "us)";
    }
}