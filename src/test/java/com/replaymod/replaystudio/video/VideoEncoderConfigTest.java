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

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VideoEncoderConfigTest {

    @Test
    public void defaults() {
        VideoEncoderConfig config = VideoEncoderConfig.builder()
                .output(new File("out.mp4"))
                .size(1920, 1080)
                .build();

        assertEquals(1920, config.getWidth());
        assertEquals(1080, config.getHeight());
        assertEquals(30, config.getFramesPerSecondNumerator());
        assertEquals(1, config.getFramesPerSecondDenominator());
        assertEquals(30.0, config.getFramesPerSecond(), 0.0001);
        assertEquals(90, config.getQuality());
        assertNull(config.getOutputChannel());
    }

    @Test
    public void exactRationalFrameRate() {
        VideoEncoderConfig config = VideoEncoderConfig.builder()
                .output(new File("out.mp4"))
                .size(640, 480)
                .frameRate(30000, 1001)
                .build();

        assertEquals(30000, config.getFramesPerSecondNumerator());
        assertEquals(1001, config.getFramesPerSecondDenominator());
        assertEquals(29.97, config.getFramesPerSecond(), 0.01);
    }

    @Test
    public void requiresAnOutput() {
        try {
            VideoEncoderConfig.builder().size(640, 480).build();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("output file or output channel"));
        }
    }

    @Test
    public void requiresASize() {
        try {
            VideoEncoderConfig.builder().output(new File("out.mp4")).build();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Frame size"));
        }
    }

    @Test
    public void rejectsOddDimensionsBecauseOfChromaSubsampling() {
        for (int[] size : new int[][]{{641, 480}, {640, 481}, {3, 3}}) {
            try {
                VideoEncoderConfig.builder().output(new File("out.mp4")).size(size[0], size[1]).build();
                fail("Expected IllegalStateException for " + size[0] + "x" + size[1]);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("even"));
            }
        }
    }

    @Test
    public void rejectsNonPositiveFrameRate() {
        VideoEncoderConfig.Builder builder = VideoEncoderConfig.builder().output(new File("out.mp4")).size(2, 2);
        try {
            builder.frameRate(0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            builder.frameRate(30, 0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsOutOfRangeQuality() {
        for (int quality : new int[]{0, 101, -5}) {
            try {
                VideoEncoderConfig.builder().quality(quality);
                fail("Expected IllegalArgumentException for quality " + quality);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void lastOutputWins() {
        File file = new File("out.mp4");
        VideoEncoderConfig config = VideoEncoderConfig.builder()
                .output(file)
                .output(file)
                .size(16, 16)
                .build();
        assertEquals(file, config.getOutputFile());
        assertNull(config.getOutputChannel());
    }

    @Test
    public void rejectsNullOutput() {
        try {
            VideoEncoderConfig.builder().output((File) null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}