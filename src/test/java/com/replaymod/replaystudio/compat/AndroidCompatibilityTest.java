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
package com.replaymod.replaystudio.compat;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards the core library against APIs that do not exist on Android.
 *
 * <p>ReplayStudio is consumed both by desktop clients and by Android apps, and Android ships neither
 * {@code java.awt}/{@code javax.imageio} (no {@code BufferedImage}, no {@code ImageIO}) nor the
 * {@code java.nio.file} package, and only gained {@code java.util.stream} in API 24. Using any of them
 * from {@code src/main} compiles fine on a desktop JVM and then fails at runtime on a device, which is
 * exactly the class of bug this test exists to prevent.</p>
 *
 * <p>Only {@code src/main} is checked. The optional {@code src/imageio} source set exists precisely to
 * hold the desktop-only implementation of the {@code ImageCodec} SPI, so it is expected to reference
 * {@code javax.imageio}.</p>
 *
 * <p>This test itself lives in {@code src/test} and is free to use any API it likes.</p>
 */
public class AndroidCompatibilityTest {

    /**
     * Fully-qualified prefixes that must not be imported or referenced from {@code src/main}.
     */
    private static final List<String> FORBIDDEN = Arrays.asList(
            "java.awt",
            "javax.imageio",
            "javax.swing",
            "java.nio.file"
    );

    /**
     * Matches an import of a forbidden API. Anchored to the start of the line and to the {@code import}
     * keyword so that Javadoc which merely mentions {@code java.awt.image.BufferedImage} in prose, or a
     * fully-qualified name inside a comment, is not flagged. Imports are what actually bind the class.
     */
    private static Pattern importPattern(String packageName) {
        return Pattern.compile("^\\s*import\\s+(static\\s+)?" + Pattern.quote(packageName) + "[.;]");
    }

    private static File findMainSourceDir() {
        // The test runs with the project root as the working directory.
        File main = new File("src/main/java");
        if (main.isDirectory()) return main;

        // Fall back to walking up from the working directory.
        File dir = new File(".").getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "src/main/java");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        return null;
    }

    @Test
    public void coreSourceSetDoesNotUseDesktopOnlyApis() throws IOException {
        File main = findMainSourceDir();
        if (main == null) {
            fail("Could not locate src/main/java from " + new File(".").getAbsolutePath());
        }

        List<String> violations = new ArrayList<>();
        for (Path java : listJavaFiles(main)) {
            List<String> lines = Files.readAllLines(java, Charset.forName("UTF-8"));
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (String forbidden : FORBIDDEN) {
                    if (importPattern(forbidden).matcher(line).find()) {
                        violations.add(main.toPath().relativize(java) + ":" + (i + 1)
                                + " imports " + forbidden + " -> " + line.trim());
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            Collections.sort(violations);
            fail("src/main must stay free of APIs that Android does not provide."
                    + " Desktop-only image support belongs in src/imageio as an ImageCodec."
                    + "\nViolations:\n  " + String.join("\n  ", violations));
        }
    }

    @Test
    public void coreSourceSetDoesNotUseJavaStreams() throws IOException {
        File main = findMainSourceDir();
        if (main == null) {
            fail("Could not locate src/main/java");
        }

        Pattern stream = Pattern.compile("^\\s*import\\s+(static\\s+)?java\\.util\\.stream[.;]");

        List<String> violations = new ArrayList<>();
        for (Path java : listJavaFiles(main)) {
            List<String> lines = Files.readAllLines(java, Charset.forName("UTF-8"));
            for (int i = 0; i < lines.size(); i++) {
                if (stream.matcher(lines.get(i)).find()) {
                    violations.add(main.toPath().relativize(java) + ":" + (i + 1)
                            + " imports java.util.stream");
                }
            }
        }

        if (!violations.isEmpty()) {
            Collections.sort(violations);
            fail("java.util.stream requires API 24 on Android unless the consuming app enables core"
                    + " library desugaring. Prefer plain loops in src/main.\nViolations:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void coreSourceSetDoesNotShellOutToExternalProcesses() throws IOException {
        File main = findMainSourceDir();
        if (main == null) {
            fail("Could not locate src/main/java");
        }

        Pattern exec = Pattern.compile("^\\s*import\\s+java\\.lang\\.ProcessBuilder\\s*;");

        List<String> violations = new ArrayList<>();
        for (Path java : listJavaFiles(main)) {
            List<String> lines = Files.readAllLines(java, Charset.forName("UTF-8"));
            for (int i = 0; i < lines.size(); i++) {
                if (exec.matcher(lines.get(i)).find()) {
                    violations.add(main.toPath().relativize(java) + ":" + (i + 1)
                            + " imports ProcessBuilder");
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Spawning processes is not available on Android.\nViolations:\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Sanity check that the guard is actually inspecting files, rather than passing vacuously because
     * the source directory could not be found or contained no Java files.
     */
    @Test
    public void guardActuallyInspectsTheCoreSources() throws IOException {
        File main = findMainSourceDir();
        assertTrue("src/main/java should exist", main != null && main.isDirectory());

        List<Path> files = listJavaFiles(main);
        assertTrue("expected src/main to contain Java sources, found " + files.size(), files.size() > 50);

        boolean sawPacket = false;
        for (Path file : files) {
            if (file.toString().replace(File.separatorChar, '/').endsWith("protocol/Packet.java")) {
                sawPacket = true;
                break;
            }
        }
        assertTrue("expected to find the core Packet class", sawPacket);
    }

    private static List<Path> listJavaFiles(File dir) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(found::add);
        }
        Collections.sort(found);
        return found;
    }
}