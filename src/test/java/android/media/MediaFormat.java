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
package android.media;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test stand-in for {@code android.media.MediaFormat}.
 *
 * <p>Lives in the {@code android.media} package only so the reflective resolution in
 * {@code MediaCodecVideoEncoder} finds it. It is never shipped: this file is in {@code src/test}.</p>
 */
public class MediaFormat {

    public final String mime;
    public final Map<String, Object> parameters = new LinkedHashMap<>();

    public MediaFormat(String mime) {
        this.mime = mime;
    }

    public void setInteger(String key, int value) {
        this.parameters.put(key, value);
    }

    public void setString(String key, String value) {
        this.parameters.put(key, value);
    }

    public int getInteger(String key) {
        Object value = this.parameters.get(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException("no integer " + key);
        return (Integer) value;
    }

    @Override
    public String toString() {
        return "MediaFormat(" + this.mime + " " + this.parameters + ")";
    }
}