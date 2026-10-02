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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Locates the {@link ImageCodec} to use for the current platform.
 *
 * <p>Codecs are discovered with {@link ServiceLoader}. The first available one becomes the default,
 * unless the application overrides it with {@link #setDefault(ImageCodec)}. Discovery failures are
 * tolerated: a codec that cannot be instantiated is skipped rather than allowed to break callers
 * that never touch images.</p>
 */
public final class ImageCodecs {

    private static final Object LOCK = new Object();
    private static volatile ImageCodec explicitDefault;
    private static volatile List<ImageCodec> discovered;

    private ImageCodecs() {}

    /**
     * Returns the codec to use, or {@code null} if the current platform provides none.
     *
     * <p>Returns the codec installed via {@link #setDefault(ImageCodec)} if there is one, and
     * otherwise the first available {@link ServiceLoader} discovery on the classpath.</p>
     *
     * @return The default codec, or {@code null} if none is available
     */
    public static ImageCodec getDefault() {
        ImageCodec explicit = explicitDefault;
        if (explicit != null) return explicit;

        for (ImageCodec codec : getDiscovered()) {
            if (isUsable(codec)) return codec;
        }
        return null;
    }

    /**
     * Returns the codec to use, failing loudly if the platform provides none.
     *
     * @return The default codec
     * @throws IllegalStateException If no codec is available on the classpath
     */
    public static ImageCodec require() {
        ImageCodec codec = getDefault();
        if (codec == null || !isUsable(codec)) {
            throw new IllegalStateException("No usable " + ImageCodec.class.getName()
                    + " is available. On Android, install one with ImageCodecs.setDefault(...)."
                    + " On desktop JVMs, add the ReplayStudio imageio artifact to the classpath.");
        }
        return codec;
    }

    /**
     * Installs the codec to use, overriding {@link ServiceLoader} discovery.
     *
     * <p>Android applications should call this during startup with an implementation backed by
     * {@code android.graphics.Bitmap}.</p>
     *
     * @param codec The codec to use, or {@code null} to fall back to discovery again
     */
    public static void setDefault(ImageCodec codec) {
        synchronized (LOCK) {
            explicitDefault = codec;
        }
    }

    /**
     * Returns the codec installed via {@link #setDefault(ImageCodec)}, if any.
     *
     * @return The explicit codec, or {@code null} if none was installed
     */
    public static ImageCodec getExplicitDefault() {
        return explicitDefault;
    }

    /**
     * Returns all codecs discovered on the classpath, excluding any explicit override.
     *
     * <p>The result is cached after the first call.</p>
     *
     * @return The discovered codecs, possibly empty
     */
    public static List<ImageCodec> getDiscovered() {
        List<ImageCodec> cached = discovered;
        if (cached != null) return cached;

        synchronized (LOCK) {
            if (discovered == null) {
                List<ImageCodec> found = new ArrayList<>();
                try {
                    for (ImageCodec codec : ServiceLoader.load(ImageCodec.class, classLoader())) {
                        if (codec != null) found.add(codec);
                    }
                } catch (Throwable ignored) {
                    // A broken service file must not prevent image-free usage of the library.
                }
                discovered = Collections.unmodifiableList(found);
            }
            return discovered;
        }
    }

    /**
     * Returns the discovered codec with the given name, ignoring case.
     *
     * @param name The name to look for
     * @return The codec, or {@code null} if no discovered codec has that name
     */
    public static ImageCodec byName(String name) {
        if (name == null) return null;
        for (ImageCodec codec : getDiscovered()) {
            if (name.equalsIgnoreCase(codec.getName())) return codec;
        }
        return null;
    }

    /**
     * Drops the discovery cache so that later calls rescan the classpath. Intended for tests.
     */
    public static void reset() {
        synchronized (LOCK) {
            discovered = null;
            explicitDefault = null;
        }
    }

    private static boolean isUsable(ImageCodec codec) {
        try {
            return codec.isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : ImageCodecs.class.getClassLoader();
    }

    /**
     * Returns whether at least one codec is available.
     *
     * @return Whether images can be encoded and decoded on this platform
     */
    public static boolean isAvailable() {
        return getDefault() != null;
    }

    /**
     * Convenience wrapper around {@link ImageCodec#encodeJpeg(Raster, int)} on the default codec.
     *
     * @param raster The image to encode
     * @param quality Quality from {@code 1} to {@code 100}
     * @return The encoded JPEG bytes
     * @throws java.io.IOException If encoding fails
     * @throws IllegalStateException If no codec is available
     */
    public static byte[] encodeJpeg(Raster raster, int quality) throws java.io.IOException {
        return require().encodeJpeg(raster, quality);
    }

    /**
     * Convenience wrapper around {@link ImageCodec#decodeJpeg(byte[])} on the default codec.
     *
     * @param data The encoded JPEG bytes
     * @return The decoded raster
     * @throws java.io.IOException If decoding fails
     * @throws IllegalStateException If no codec is available
     */
    public static Raster decodeJpeg(byte[] data) throws java.io.IOException {
        return require().decodeJpeg(data);
    }
}