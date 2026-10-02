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

import java.io.IOException;

/**
 * Encodes and decodes images for platforms that do not provide a built-in image codec.
 *
 * <p>ReplayStudio needs JPEG support to read and write replay thumbnails. Desktop JVMs can do this
 * with {@code javax.imageio} and Android can do it with {@code android.graphics.Bitmap}, but
 * neither API exists on the other platform, so both are kept out of this library's core. This
 * interface is the seam between the two.</p>
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and are normally registered
 * by placing a {@code META-INF/services/com.replaymod.replaystudio.image.ImageCodec} resource on
 * the classpath. Android applications, which cannot ship the desktop implementation, are expected
 * to call {@link ImageCodecs#setDefault(ImageCodec)} with their own {@code Bitmap}-backed
 * implementation during startup.</p>
 *
 * @see ImageCodecs
 */
public interface ImageCodec {

    /**
     * Returns a simple, unique and stable name for this implementation.
     *
     * @return The name
     */
    String getName();

    /**
     * Returns whether this implementation can be used in the current environment.
     *
     * <p>Implementations that link against optional platform APIs should return {@code false} rather
     * than failing to load when those APIs are missing.</p>
     *
     * @return Whether this codec is usable
     */
    boolean isAvailable();

    /**
     * Encodes the given raster as a JPEG image.
     *
     * @param raster The image to encode
     * @param quality Quality from {@code 1} (smallest, worst) to {@code 100} (largest, best)
     * @return The encoded JPEG bytes
     * @throws IOException If encoding fails or the platform cannot represent the image
     */
    byte[] encodeJpeg(Raster raster, int quality) throws IOException;

    /**
     * Decodes a JPEG image.
     *
     * @param data The encoded JPEG bytes
     * @return The decoded raster
     * @throws IOException If decoding fails or the data is not a valid JPEG image
     */
    Raster decodeJpeg(byte[] data) throws IOException;
}