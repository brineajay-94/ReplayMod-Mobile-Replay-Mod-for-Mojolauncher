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
 * Converts a {@link VideoFrame} into planar YUV 4:2:0 (I420), the input format Android's
 * {@code MediaCodec} expects.
 *
 * <p>Kept separate from the encoder so it is plain Java and directly testable on a desktop JVM, which
 * matters because {@link MediaCodecVideoEncoder} itself can only run on a device.</p>
 *
 * <p>Uses BT.601 with limited ("TV") range, coefficients identical to those FFmpeg's {@code swscale}
 * uses for {@code yuv420p}. For a pure red input of {@code (220, 20, 20)} this yields
 * {@code Y=85 U=98 V=216}, and FFmpeg produces exactly those values, so the two agree bit for bit on
 * flat colours. Y is clamped to 16..235 and chroma to 16..240, which is what limited range
 * requires.</p>
 *
 * <p>The converter is stateless apart from a caller-supplied scratch buffer, so one instance can be
 * reused for every frame.</p>
 */
public class Yuv420Converter {

    /** Reusable luma plane, allocated on first use and kept for subsequent frames. */
    private byte[] luma;

    /**
     * Converts the given frame into I420 and returns the result.
     *
     * <p>The returned array is this converter's own buffer and is overwritten by the next call, so the
     * caller must consume it before converting another frame.</p>
     *
     * @param frame The frame to convert
     * @return Tightly packed I420: {@code width * height} luma bytes then {@code (width / 2) * (height / 2)}
     *         bytes of Cb followed by the same of Cr
     */
    public byte[] toI420(VideoFrame frame) {
        int width = frame.getWidth(), height = frame.getHeight();
        int lumaSize = width * height;
        int chromaWidth = width / 2, chromaHeight = height / 2;
        int chromaSize = chromaWidth * chromaHeight;

        byte[] out = new byte[lumaSize + 2 * chromaSize];
        if (this.luma == null || this.luma.length != lumaSize) {
            this.luma = new byte[lumaSize];
        }
        byte[] y = this.luma;

        // Sum of the four source pixels in each 2x2 block, so chroma averages rather than samples and
        // avoids aliasing on high-frequency detail.
        int[] blockR = new int[chromaSize];
        int[] blockG = new int[chromaSize];
        int[] blockB = new int[chromaSize];

        PixelFormat format = frame.getFormat();
        byte[] src = frame.getData();
        int bpp = format.getBytesPerPixel();

        for (int row = 0; row < height; row++) {
            int srcRowBase = row * width * bpp;
            int dstRowBase = row * width;
            for (int column = 0; column < width; column++) {
                int at = srcRowBase + column * bpp;
                int r, g, b;
                switch (format) {
                    case ARGB8888:
                        // In memory an 0xAARRGGBB int is B, G, R, A.
                        b = src[at] & 0xFF;
                        g = src[at + 1] & 0xFF;
                        r = src[at + 2] & 0xFF;
                        break;
                    case RGBA:
                    case RGB888:
                        r = src[at] & 0xFF;
                        g = src[at + 1] & 0xFF;
                        b = src[at + 2] & 0xFF;
                        break;
                    case RGB24:
                        r = src[at] & 0xFF;
                        g = src[at + 1] & 0xFF;
                        b = src[at + 2] & 0xFF;
                        break;
                    case BGR24:
                        b = src[at] & 0xFF;
                        g = src[at + 1] & 0xFF;
                        r = src[at + 2] & 0xFF;
                        break;
                    case YUV420:
                        throw new IllegalArgumentException(
                                "A frame that is already YUV420 should be passed through, not converted");
                    default:
                        throw new IllegalArgumentException("Unhandled pixel format " + format);
                }

                y[dstRowBase + column] = (byte) luma(r, g, b);

                int blockIndex = (row / 2) * chromaWidth + (column / 2);
                blockR[blockIndex] += r;
                blockG[blockIndex] += g;
                blockB[blockIndex] += b;
            }
        }

        System.arraycopy(y, 0, out, 0, lumaSize);
        int cbBase = lumaSize;
        int crBase = lumaSize + chromaSize;
        for (int i = 0; i < chromaSize; i++) {
            out[cbBase + i] = (byte) chromaB(blockR[i] >> 2, blockG[i] >> 2, blockB[i] >> 2);
            out[crBase + i] = (byte) chromaR(blockR[i] >> 2, blockG[i] >> 2, blockB[i] >> 2);
        }
        return out;
    }

    /**
     * Converts BT.601 limited-range luma.
     *
     * @param r Red, 0..255
     * @param g Green, 0..255
     * @param b Blue, 0..255
     * @return Luma clamped to 16..235
     */
    public static int luma(int r, int g, int b) {
        double value = 16.0 + (65.738 * r + 129.057 * g + 25.064 * b) / 256.0;
        return clamp(value, 16, 235);
    }

    /**
     * Converts BT.601 limited-range Cb, the blue-difference chroma channel.
     *
     * @param r Red, 0..255
     * @param g Green, 0..255
     * @param b Blue, 0..255
     * @return Cb clamped to 16..240
     */
    public static int chromaB(int r, int g, int b) {
        double value = 128.0 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256.0;
        return clamp(value, 16, 240);
    }

    /**
     * Converts BT.601 limited-range Cr, the red-difference chroma channel.
     *
     * @param r Red, 0..255
     * @param g Green, 0..255
     * @param b Blue, 0..255
     * @return Cr clamped to 16..240
     */
    public static int chromaR(int r, int g, int b) {
        double value = 128.0 + (112.439 * r - 94.154 * g - 18.285 * b) / 256.0;
        return clamp(value, 16, 240);
    }

    private static int clamp(double value, int min, int max) {
        int rounded = (int) Math.round(value);
        if (rounded < min) return min;
        if (rounded > max) return max;
        return rounded;
    }
}