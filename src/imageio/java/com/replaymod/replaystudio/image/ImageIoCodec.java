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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * {@link ImageCodec} backed by {@code javax.imageio}, for desktop JVMs.
 *
 * <p>This class deliberately lives in a separate source set rather than in {@code src/main} so that
 * the core library keeps no compile-time reference to {@code java.awt} or {@code javax.imageio},
 * neither of which exists on Android. Desktop consumers get this implementation by depending on the
 * {@code imageio} artifact; Android consumers supply their own {@code Bitmap}-backed codec instead.</p>
 */
public class ImageIoCodec implements ImageCodec {

    private static final String FORMAT = "jpeg";
    private static final float DEFAULT_QUALITY = 0.75f;

    @Override
    public String getName() {
        return "imageio";
    }

    @Override
    public boolean isAvailable() {
        return ImageIO.getImageWritersByFormatName(FORMAT).hasNext()
                && ImageIO.getImageReadersByFormatName(FORMAT).hasNext();
    }

    @Override
    public byte[] encodeJpeg(Raster raster, int quality) throws IOException {
        if (raster == null) throw new IllegalArgumentException("Raster must not be null");
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("Quality must be within [1, 100] but was " + quality);
        }

        // JPEG has no alpha channel, so the raster must be flattened to an opaque image before
        // handing it to a writer. TYPE_INT_RGB drops the alpha component and keeps the colour
        // channels as-is, which preserves fully transparent pixels' colours instead of compositing
        // them onto black.
        BufferedImage image = new BufferedImage(raster.getWidth(), raster.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, raster.getWidth(), raster.getHeight(), raster.getPixels(), 0, raster.getWidth());

        Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName(FORMAT);
        if (!writers.hasNext()) {
            // No JPEG writer: fall back to letting ImageIO pick one and use its default quality.
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            if (!ImageIO.write(image, FORMAT, plain)) {
                throw new IOException("No JPEG image writer is available in this JVM");
            }
            return plain.toByteArray();
        }

        javax.imageio.ImageWriter writer = writers.next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality / 100f);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageOutputStream out = ImageIO.createImageOutputStream(bytes);
            if (out == null) throw new IOException("Could not create an in-memory image output stream");
            try {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), params);
                out.flush();
            } finally {
                out.close();
                writer.dispose();
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    @Override
    public Raster decodeJpeg(byte[] data) throws IOException {
        if (data == null) throw new IllegalArgumentException("Data must not be null");

        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (image == null) throw new IOException("Data is not a readable JPEG image");

        int width = image.getWidth(), height = image.getHeight();
        int[] argb = new int[width * height];
        image.getRGB(0, 0, width, height, argb, 0, width);
        return new Raster(width, height, argb);
    }
}