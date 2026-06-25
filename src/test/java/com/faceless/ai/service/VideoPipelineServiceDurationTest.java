package com.faceless.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the core promise of the pipeline: a rendered scene clip is exactly
 * as long as its narration audio — the video never plays on after the voice
 * ends, and never gets cut short either.
 *
 * <p>These tests drive the <em>real</em> ffmpeg-backed
 * {@link VideoPipelineService} methods against synthetic inputs generated on
 * the fly (silent audio of a known length, solid-colour images, a short looping
 * source clip), so they need an {@code ffmpeg}/{@code ffprobe} on PATH. When
 * those binaries are absent — e.g. a minimal CI image — the tests skip rather
 * than fail.
 *
 * <p>{@code assembleScene}/{@code assembleSceneFromVideo} write to the fixed
 * {@code CLIPS_DIR}; each test uses a unique job id and deletes its own clip in
 * teardown so it never touches real pipeline output.
 */
class VideoPipelineServiceDurationTest {

    /**
     * Tolerance for AAC re-encode padding. One AAC frame is 1024 samples
     * (~23 ms at 44.1 kHz); a couple of frames of priming/padding is normal, so
     * we allow a small window around the true narration length.
     */
    private static final double TOLERANCE_SEC = 0.30;

    /**
     * Hard regression ceiling. The old code added a 0.5 s buffer to the video
     * with no {@code -shortest}, so clips ran ~0.5 s past the audio. A clip that
     * exceeds the narration by more than this would mean that bug is back.
     */
    private static final double MAX_OVERSHOOT_SEC = 0.25;

    private VideoPipelineService svc;
    private final String jobId = "junit-" + UUID.randomUUID();
    private final List<Path> clipsToCleanUp = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(toolExists("ffmpeg") && toolExists("ffprobe"),
                "ffmpeg/ffprobe not on PATH — skipping media duration tests");
        // ttsService is unused by the assembly/probe methods under test.
        svc = new VideoPipelineService(null);
        Files.createDirectories(Paths.get(VideoPipelineService.CLIPS_DIR));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path clip : clipsToCleanUp) {
            Files.deleteIfExists(clip);
        }
    }

    @Test
    void imageSceneClipMatchesNarrationLength(@TempDir Path tmp) throws Exception {
        // 5 s of audio across 3 images => 5/3 s per image: exercises the
        // duration-per-image rounding that previously left the video long.
        double audioSeconds = 5.0;
        Path audio = makeSilentAudio(tmp.resolve("voice.mp3"), audioSeconds);
        List<Path> images = List.of(
                makeImage(tmp.resolve("img0.jpg"), "red"),
                makeImage(tmp.resolve("img1.jpg"), "green"),
                makeImage(tmp.resolve("img2.jpg"), "blue"));

        double narration = svc.getAudioDurationSeconds(audio);
        Path clip = svc.assembleScene(images, audio, jobId, 1, narration, null);
        clipsToCleanUp.add(clip);

        double clipDuration = svc.getAudioDurationSeconds(clip);

        assertThat(clipDuration)
                .as("scene clip should equal narration length")
                .isCloseTo(narration, within(TOLERANCE_SEC));
        assertThat(clipDuration)
                .as("scene clip must not run past the narration (regression: old +0.5s overshoot)")
                .isLessThanOrEqualTo(narration + MAX_OVERSHOOT_SEC);
    }

    @Test
    void videoSceneClipMatchesNarrationLengthWhenSourceIsShorter(@TempDir Path tmp) throws Exception {
        // Source clip (2 s) is shorter than the narration (6 s): the assembler
        // must loop it and still end exactly at the audio.
        double audioSeconds = 6.0;
        Path audio = makeSilentAudio(tmp.resolve("voice.mp3"), audioSeconds);
        Path source = makeSolidVideo(tmp.resolve("source.mp4"), 2.0, "blue");

        double narration = svc.getAudioDurationSeconds(audio);
        Path clip = svc.assembleSceneFromVideo(source, audio, jobId, 2, narration, null);
        clipsToCleanUp.add(clip);

        double clipDuration = svc.getAudioDurationSeconds(clip);

        assertThat(clipDuration)
                .as("video-mode clip should equal narration length")
                .isCloseTo(narration, within(TOLERANCE_SEC));
        assertThat(clipDuration)
                .as("video-mode clip must not run past the narration")
                .isLessThanOrEqualTo(narration + MAX_OVERSHOOT_SEC);
    }

    @Test
    void audioDurationIsReportedUnpadded(@TempDir Path tmp) throws Exception {
        // The metadata fix: getAudioDurationSeconds must return the TRUE length,
        // not length + 0.5. Guard the 0.5 buffer from creeping back in.
        double audioSeconds = 4.0;
        Path audio = makeSilentAudio(tmp.resolve("voice.mp3"), audioSeconds);

        double reported = svc.getAudioDurationSeconds(audio);

        assertThat(reported)
                .as("reported duration must be the real length, with no padding")
                .isCloseTo(audioSeconds, within(0.2));
    }

    // ------------------------------------------------------------------ //
    //  Synthetic media + process helpers
    // ------------------------------------------------------------------ //

    private Path makeSilentAudio(Path out, double seconds) throws Exception {
        run("ffmpeg", "-y", "-f", "lavfi",
                "-i", "anullsrc=r=44100:cl=mono",
                "-t", String.valueOf(seconds),
                out.toString());
        return out;
    }

    private Path makeImage(Path out, String colour) throws Exception {
        run("ffmpeg", "-y", "-f", "lavfi",
                "-i", "color=c=" + colour + ":s=640x480",
                "-frames:v", "1",
                out.toString());
        return out;
    }

    private Path makeSolidVideo(Path out, double seconds, String colour) throws Exception {
        run("ffmpeg", "-y", "-f", "lavfi",
                "-i", "color=c=" + colour + ":s=640x480:r=25:d=" + seconds,
                "-pix_fmt", "yuv420p",
                out.toString());
        return out;
    }

    private static boolean toolExists(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-version")
                    .redirectErrorStream(true).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(
                    "Command failed (" + p.exitValue() + "): " + String.join(" ", cmd) + "\n" + output);
        }
    }
}