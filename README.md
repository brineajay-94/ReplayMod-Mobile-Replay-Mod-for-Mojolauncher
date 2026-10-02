# ReplayMod Mobile — video encoding and Android compatibility

Built-in video encoding for Minecraft replays, so a replay can be rendered straight to a video file
**without installing `ffmpeg`** — including on Android, where installing it is not possible.

This repository contains only the code added for that work. It is an **addition to
[ReplayStudio](https://github.com/ReplayMod/ReplayStudio)**, ReplayMod's replay-file library, and is
not a standalone build.

## What this is, and what it is not

**It is not a Minecraft mod, and not a mobile ReplayMod.** It is a set of packages that get dropped
into ReplayStudio. The pieces that render Minecraft frames and present a UI live in the
[ReplayMod](https://github.com/ReplayMod/ReplayMod) repository, not here.

**It does not build standalone**, and deliberately ships no Gradle build. The sources compile inside
ReplayStudio because they use its relocated dependencies (JCodec under
`com.replaymod.replaystudio.lib.jcodec`) and reference its `Studio` interface. Copying these files
into a ReplayStudio checkout works; running `./gradlew` here does not.

If you want a buildable artifact, use a ReplayStudio fork that includes these files.

## Why this exists

Rendering a replay traditionally shells out to an `ffmpeg` binary. That works on a desktop and fails on
Android, where you cannot install one. [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)
does run ReplayMod on Android, and works around this by shipping an optional FFmpeg plugin. This code
removes the need for that plugin by using the encoder that is already part of Android.

## Encoders

| Name | Platform | Status |
| --- | --- | --- |
| `mediacodec` | Android 5.0+ (API 21) | Working. Uses Android's hardware H.264 encoder. Nothing to install, no bundled native libraries. Reached by reflection, so ReplayStudio itself gains no Android dependency. |
| `jcodec` | Any JVM | **Disabled.** Pure Java, but produces wrong colours. See below. |

Desktop gets no built-in encoder: encode PNG frames and pipe them to a locally installed `ffmpeg`, or
implement `VideoEncoder` yourself.

### The `jcodec` backend is disabled on purpose

JCodec 0.2.5 is the only pure-Java H.264 encoder available for Java, and it renders the **wrong
colours**. Verified against FFmpeg 8.1.1 as an independent reference, for a pure red `(220,20,20)`
frame:

| | Y | U | V |
| --- | --- | --- | --- |
| Correct (BT.601 limited range) | 85 | 98 | 216 |
| JCodec emits | 251 | 9 | 228 |

Decoding JCodec's output with FFmpeg returns red as cyan, green as magenta and blue as yellow.
Ruled out as causes: a missing `Picture` crop, `ColorSpace.BGR` (rejected outright by its
`SequenceEncoder`), chroma plane sizing, and bypassing its transform entirely by feeding
hand-computed YUV420J planes to its low-level `H264Encoder` — which was also wrong. The container,
frame count and per-pixel format mapping are all correct; only absolute colour is broken. JCodec has
had essentially no releases since 0.2.5 in 2019.

It is therefore not registered as a service, and `open()` throws unless
`-Dreplaystudio.video.allowBrokenJcodec=true` is set. The full evidence is in `JcodecVideoEncoder.DEFECT`.

There is no viable pure-Java route to colour-accurate H.264, which is why the Android backend exists.

## Usage

```java
Studio studio = new ReplayStudio();
VideoEncoder encoder = studio.loadVideoEncoder("mediacodec");
VideoEncoderConfig config = VideoEncoderConfig.builder()
        .output(new File("render.mp4"))
        .size(1920, 1080)
        .frameRate(60)
        .build();

encoder.open(config);
FrameSink sink = FrameSink.forEncoder(encoder);
for (VideoFrame frame : renderedFrames) {   // your renderer supplies these
    sink.accept(frame);
}
encoder.close();                             // finalises the file
```

Frames are raw pixels in one of the `PixelFormat` layouts, so no `BufferedImage` or `Bitmap` is
involved and the same renderer code works on both platforms. An encoder reads a frame's pixel array
only for the duration of `writeFrame`, so a renderer can recycle its OpenGL readback buffer
immediately.

The output must be **seekable**, because muxing MP4 means rewriting box sizes once their contents are
known. Pass a `File` or a `FileChannel`; a plain `OutputStream` cannot work.

### Colour

RGB frames are converted to YUV 4:2:0 by `Yuv420Converter` — BT.601 limited range, using the same
coefficients FFmpeg uses, with chroma averaged over 2x2 blocks. This is deliberately done by us
rather than the platform, because it is the step the JCodec path gets wrong. `Yuv420ConverterTest`
pins the output to FFmpeg's values (red `Y=85 U=98 V=216`, green `134/70/54`, blue `53/216/114`).

### Audio

Not supported. There is no viable pure-Java AAC encoder, so the pipeline is video-only. `MediaCodec`
can encode AAC on Android, so audio is feasible there later; it is not feasible in a pure-Java backend.

## Layout

```
src/main/java/com/replaymod/replaystudio/video/   encoder SPI, frame model, MediaCodec + JCodec backends
src/main/java/com/replaymod/replaystudio/image/   Raster and the ImageCodec SPI
src/imageio/                                      desktop javax.imageio ImageCodec (separate source set)
src/test/java/android/                            stand-ins for the Android classes
src/test/java/com/replaymod/replaystudio/         tests
```

## Testing

112 tests, of which 1 is disabled with the JCodec colour defect documented in its javadoc.

`MediaCodecVideoEncoder` is reached entirely by reflection against the thread context class loader, so
its state machine can be exercised **without a device**. `src/test/java/android` contains stand-ins for
the platform classes that model the parts of the contract which are easy to get wrong: input buffers
are only available when free, encoded samples surface asynchronously, the output format is announced
exactly once, and end of stream only follows an explicit signal. `MediaCodecVideoEncoderFakeTest`
drives the production reflection path, so a regression in the configure/feed/drain/EOS sequencing fails
there rather than on a phone.

Running the suite on real hardware is still worth doing, and is the one thing the fakes cannot cover:
some encoders require dimensions that are a multiple of 16 rather than merely even, and some expect
semi-planar NV12 rather than planar I420. `open` surfaces the platform's own exception in those cases
rather than hiding it.

## License

LGPL-3.0, matching ReplayStudio. `COPYING`, `COPYING.LESSER` and `LICENSE.legacy` are included
because this code derives from ReplayStudio; `LICENSE.legacy` covers the pre-existing MIT-licensed
portions only.