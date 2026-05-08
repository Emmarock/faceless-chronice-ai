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
     * than the narration; {@code -t durationSec} truncates the result. The
     * source's own audio is dropped via {@code -map 0:v}, then the narration
     * track is mapped from the second input.
     */
    public Path assembleSceneFromVideo(Path sourceVideo, Path audio, String jobId, int sceneId,
                                       double durationSec) throws Exception {
        log.info("Assembling clip from source video for job {} scene {} ({} s)...", jobId, sceneId, durationSec);

        Path clipPath = Paths.get(CLIPS_DIR, "clip_" + jobId + "_scene_" + sceneId + ".mp4");

        String filter = "[0:v]scale=1280:720:force_original_aspect_ratio=decrease,"
                + "pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1[v]";

        String[] cmd = {
                "ffmpeg", "-y",
                // Loop the source clip in case the narration is longer than it.
                "-stream_loop", "-1",
                "-i", sourceVideo.toString(),
                "-i", audio.toString(),
                "-filter_complex", filter,
                "-map", "[v]",
                "-map", "1:a",
                // Truncate to narration length even when the source is longer.
                "-t", String.valueOf(durationSec),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-threads", "1",
                "-x264-params", "rc-lookahead=10:ref=1",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                clipPath.toString()
        };

        runProcess(cmd);
        return clipPath;
    }

    public Path assembleScene(List<Path> images, Path audio, String jobId, int sceneId,
                              double durationSec) throws Exception {
        int n = images.size();
        log.info("Assembling clip for job {} scene {} ({} s, {} images)...", jobId, sceneId, durationSec, n);

        Path clipPath = Paths.get(CLIPS_DIR, "clip_" + jobId + "_scene_" + sceneId + ".mp4");
        double durationPerImage = durationSec / n;

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");

        for (Path image : images) {
            cmd.add("-loop"); cmd.add("1");
            cmd.add("-t"); cmd.add(String.valueOf(durationPerImage));
            cmd.add("-i"); cmd.add(image.toString());
        }
        cmd.add("-i"); cmd.add(audio.toString());

        // Scale each image to 1280x720 with letterboxing, then concat into one stream
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
        filter.append("concat=n=").append(n).append(":v=1:a=0[v]");

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

    /** Returns actual audio duration + 0.5 s buffer via ffprobe. */
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
            return Double.parseDouble(output) + 0.5;
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