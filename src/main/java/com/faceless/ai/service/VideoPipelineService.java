package com.faceless.ai.service;

import com.faceless.ai.service.image.ImageGenerationService;
import com.faceless.ai.service.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Low-level media utilities shared across pipeline consumers.
 * Each stage consumer (audio, image, combine) calls into this service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoPipelineService {

    public static final String VOICE_DIR = "./output/files/voices/";
    public static final String CLIPS_DIR = "./output/files/clips/";
    public static final String FINAL_DIR = "./output/files/final/";

    /**
     * Seconds of extra video laid down past the narration before {@code -shortest}
     * trims the output back to the audio length. This guards against the looped
     * images falling a few frames short of the audio (float rounding of
     * {@code durationSec / n}), which would otherwise let {@code -shortest} clip
     * the tail of the narration. It is never present in the final clip — the
     * muxer stops at the (shorter) audio stream.
     */
    private static final double VIDEO_COVERAGE_PAD_SEC = 0.5;

    private final TtsService ttsService;

    // ------------------------------------------------------------------ //
    //  Voice generation — delegated to the configured TtsService impl
    // ------------------------------------------------------------------ //

    public Path generateVoice(String text, String jobId, int sceneId) throws Exception {
        return ttsService.generateVoice(text, jobId, sceneId);
    }

    // ------------------------------------------------------------------ //
    //  Scene assembly: image + audio → MP4 clip
    // ------------------------------------------------------------------ //

    /**
     * Video-mode scene assembly: take a single source clip, trim/loop it to
     * the narration length, replace its audio track with the narration, scale
     * to the standard 1280×720 canvas (with letterboxing if needed), and
     * write a per-scene MP4. Mirrors {@link #assembleScene} so the cached
     * VIDEO_CLIP handling in {@code VideoCombineConsumer} works identically
     * for both modes.
     *
     * <p>{@code -stream_loop -1} loops the input indefinitely if it's shorter
     * than the narration; {@code -shortest} then ends the clip exactly at the
     * narration so the video never outruns the audio. The source's own audio is
     * dropped via {@code -map 0:v}, then the narration track is mapped from the
     * second input.
     */
    public Path assembleSceneFromVideo(Path sourceVideo, Path audio, String jobId, int sceneId,
                                       double durationSec) throws Exception {
        return assembleSceneFromVideo(sourceVideo, audio, jobId, sceneId, durationSec, null);
    }

    /**
     * {@link #assembleSceneFromVideo(Path, Path, String, int, double)} with
     * an optional watermark string. When {@code watermarkText} is non-blank,
     * a {@code drawtext} pass is appended to the per-scene filter chain so
     * every clip carries the overlay. The concat step at the end of the
     * pipeline uses {@code -c copy} and can't add filters, so per-scene is
     * the only place this hook makes sense.
     */
    public Path assembleSceneFromVideo(Path sourceVideo, Path audio, String jobId, int sceneId,
                                       double durationSec, String watermarkText) throws Exception {
        log.info("Assembling clip from source video for job {} scene {} ({} s, watermark={})",
                jobId, sceneId, durationSec, watermarkText != null);

        Path clipPath = Paths.get(CLIPS_DIR, "clip_" + jobId + "_scene_" + sceneId + ".mp4");

        String filter = "[0:v]scale=1280:720:force_original_aspect_ratio=decrease,"
                + "pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1"
                + watermarkChain(watermarkText)
                + "[v]";

        String[] cmd = {
                "ffmpeg", "-y",
                // Loop the source clip in case the narration is longer than it.
                "-stream_loop", "-1",
                "-i", sourceVideo.toString(),
                "-i", audio.toString(),
                "-filter_complex", filter,
                "-map", "[v]",
                "-map", "1:a",
                // Cap the looped video just past the narration, then let
                // -shortest end the clip exactly at the audio so it never
                // plays on after the voice finishes.
                "-t", String.valueOf(durationSec + VIDEO_COVERAGE_PAD_SEC),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-threads", "1",
                "-x264-params", "rc-lookahead=10:ref=1",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                clipPath.toString()
        };

        runProcess(cmd);
        return clipPath;
    }

    public Path assembleScene(List<Path> images, Path audio, String jobId, int sceneId,
                              double durationSec) throws Exception {
        return assembleScene(images, audio, jobId, sceneId, durationSec, null);
    }

    public Path assembleScene(List<Path> images, Path audio, String jobId, int sceneId,
                              double durationSec, String watermarkText) throws Exception {
        int n = images.size();
        log.info("Assembling clip for job {} scene {} ({} s, {} images, watermark={})",
                jobId, sceneId, durationSec, n, watermarkText != null);

        Path clipPath = Paths.get(CLIPS_DIR, "clip_" + jobId + "_scene_" + sceneId + ".mp4");
        // Lay the images down slightly past the narration so they always cover
        // its full length; -shortest (below) trims the muxed output back to the
        // audio so the video never plays on after the voice ends.
        double durationPerImage = (durationSec + VIDEO_COVERAGE_PAD_SEC) / n;

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");

        for (Path image : images) {
            cmd.add("-loop"); cmd.add("1");
            cmd.add("-t"); cmd.add(String.valueOf(durationPerImage));
            cmd.add("-i"); cmd.add(image.toString());
        }
        cmd.add("-i"); cmd.add(audio.toString());

        // Scale each image to 1280x720 with letterboxing, then concat into one
        // stream. When a watermark is requested, append the drawtext pass
        // AFTER concat so a single overlay sits at the same position across
        // the whole scene (vs. once per image, which would flicker on cuts).
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < n; i++) {
            filter.append("[").append(i).append(":v]")
                  .append("scale=1280:720:force_original_aspect_ratio=decrease,")
                  .append("pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1")
                  .append("[v").append(i).append("];");
        }
        for (int i = 0; i < n; i++) {
            filter.append("[v").append(i).append("]");
        }
        if (watermarkText != null && !watermarkText.isBlank()) {
            filter.append("concat=n=").append(n).append(":v=1:a=0[concatv];");
            filter.append("[concatv]").append(drawTextFilterBody(watermarkText)).append("[v]");
        } else {
            filter.append("concat=n=").append(n).append(":v=1:a=0[v]");
        }

        cmd.add("-filter_complex"); cmd.add(filter.toString());
        cmd.add("-map"); cmd.add("[v]");
        cmd.add("-map"); cmd.add(n + ":a");
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("veryfast");
        cmd.add("-threads"); cmd.add("1");
        cmd.add("-x264-params"); cmd.add("rc-lookahead=10:ref=1");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("192k");
        // End the clip with the shortest stream (the narration) so the looped
        // images don't keep playing after the audio finishes.
        cmd.add("-shortest");
        cmd.add(clipPath.toString());

        runProcess(cmd.toArray(new String[0]));
        return clipPath;
    }

    // ------------------------------------------------------------------ //
    //  Final concatenation
    // ------------------------------------------------------------------ //

    public Path concatenateScenes(List<String> clipPaths, String jobId) throws Exception {
        log.info("Concatenating {} scene clips...", clipPaths.size());

        Path listFile = Paths.get(CLIPS_DIR, "concat_" + jobId + ".txt");
        List<String> lines = clipPaths.stream()
                .map(p -> "file '" + Paths.get(p).toAbsolutePath() + "'")
                .toList();
        Files.write(listFile, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path finalVideo = Paths.get(FINAL_DIR, "final_" + jobId + ".mp4");

        String[] cmd = {
                "ffmpeg", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                finalVideo.toString()
        };

        runProcess(cmd);
        log.info("Final video: {}", finalVideo);
        return finalVideo;
    }

    // ------------------------------------------------------------------ //
    //  Duration helpers
    // ------------------------------------------------------------------ //

    /**
     * Returns the actual audio (narration) duration in seconds via ffprobe.
     *
     * <p>This is the single source of truth for "how long should this scene
     * be". It is deliberately unpadded: the scene assemblers add a tiny
     * coverage pad to the <em>video</em> stream and then use {@code -shortest}
     * to trim the muxed output back to exactly this audio length, so the video
     * never outruns the narration. Stored/aggregated durations therefore match
     * what the viewer actually hears.
     */
    public double getAudioDurationSeconds(Path audioPath) throws Exception {
        String[] cmd = {
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioPath.toString()
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            log.warn("Could not parse audio duration '{}', falling back to 5 s", output);
            return 5.0;
        }
    }

    public double getTotalDurationSeconds(List<String> clipPaths) {
        double total = 0;
        for (String path : clipPaths) {
            try {
                total += getAudioDurationSeconds(Paths.get(path));
            } catch (Exception e) {
                log.warn("Could not read duration for {}", path);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ //
    //  Utilities
    // ------------------------------------------------------------------ //

    public void ensureOutputDirs() throws Exception {
        for (String dir : new String[]{VOICE_DIR, ImageGenerationService.IMAGE_OUTPUT_DIR, CLIPS_DIR, FINAL_DIR}) {
            Path p = Paths.get(dir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Watermark filter helpers
    //
    //  drawtext is part of every modern ffmpeg build that includes
    //  --enable-libfreetype (the prebuilt static binaries from BtbN /
    //  jrottenberg do). If you ship a stripped build that lacks freetype,
    //  swap this to an `overlay` filter against a PNG asset.
    // ------------------------------------------------------------------ //

    /**
     * Returns a filter snippet that can be appended to a per-source filter
     * chain (between {@code setsar=1} and the trailing {@code [v]}). Empty
     * string when no watermark is requested so callers can concatenate
     * unconditionally.
     */
    private static String watermarkChain(String text) {
        if (text == null || text.isBlank()) return "";
        return "," + drawTextFilterBody(text);
    }

    /**
     * The drawtext filter body alone (no leading comma, no node labels).
     * Use {@link #watermarkChain(String)} when concatenating inside a
     * comma-chained filter; use this directly when wiring a fresh node.
     */
    private static String drawTextFilterBody(String text) {
        // Strip / replace characters that would terminate a drawtext expression
        // mid-arg (colon, percent, single-quote, backslash). Keeping this
        // defensive so a future user-configurable watermark string can't
        // break the filter graph.
        String safe = text.replaceAll("[:%\\\\']", " ").trim();
        if (safe.isBlank()) safe = "Faceless Chronicle AI";
        return "drawtext=text='" + safe + "'"
                + ":fontcolor=white@0.85"
                + ":fontsize=22"
                + ":x=w-tw-20"
                + ":y=h-th-20"
                + ":shadowcolor=black@0.7"
                + ":shadowx=2:shadowy=2";
    }

    private void runProcess(String[] cmd) throws Exception {
        Process process = new ProcessBuilder(cmd)
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Process exited with code " + exitCode + ": " + Arrays.toString(cmd));
        }
    }
}