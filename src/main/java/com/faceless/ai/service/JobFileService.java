package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.Job;
import com.faceless.ai.entity.Script;
import com.faceless.ai.entity.Status;
import com.faceless.ai.model.*;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.JobRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobFileService {

    private final ObjectMapper objectMapper;
    private final JobService jobService;
    private final ChatGPTService chatGPTService;
    private final ChatGPTMapper chatGPTMapper;
    private final PipelineProducer pipelineProducer;
    private final VideoRepository videoRepository;
    private final AssetRepository assetRepository;
    private final JobRepository jobRepository;
    private final S3StorageService s3StorageService;

    private static final String JOB_OUTPUT_DIR = "./output/files/jobs/job_";

    // ------------------------------------------------------------------ //
    //  New job
    // ------------------------------------------------------------------ //

    public JobFileDTO generateJobFile(GenerateJobRequest request, String createdBy) throws Exception {
        // 1. Save job entity
        Job job = jobService.createJob(request, createdBy);
        log.info("Job created: {}", job);

        // 2. Call ChatGPT to generate script
        String chatGPTResponse = chatGPTService.generateScript(request.getQuestion(), request.getStyle());
        log.info("Script generated for job {}: {}", job.getId(), chatGPTResponse);
        VideoScript videoScript = chatGPTMapper.mapToVideoScript(chatGPTResponse);

        // 3. Persist script
        jobService.saveScript(job, objectMapper.writeValueAsString(videoScript), createdBy);

        // 4. Write JSON to file
        Path jobDir = Paths.get(JOB_OUTPUT_DIR);
        if (!jobDir.toFile().exists()) {
            jobDir.toFile().mkdirs();
        }
        Path jobFile = Paths.get(JOB_OUTPUT_DIR + job.getId() + ".json");
        Files.createDirectories(jobFile.getParent());
        UUID connectionId = job.getSocialConnection() != null ? job.getSocialConnection().getId() : null;
        JobFileDTO jobJson = new JobFileDTO(job.getId(), createdBy, jobFile, videoScript, connectionId);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobFile.toFile(), jobJson);
        log.info("Job file saved at: {} — pipeline will start when the user resumes the job.", jobFile);

        // The voice / image / video pipeline is intentionally NOT triggered here.
        // The user reviews and edits the script first, then calls /resume to kick it off.
        return jobJson;
    }

    // ------------------------------------------------------------------ //
    //  Read APIs (no queue side-effects)
    // ------------------------------------------------------------------ //

    /**
     * Returns the latest persisted {@link JobFileDTO} for a job — the stored
     * VideoScript with any saved voice / image / video URLs already merged in.
     * Used by the frontend to load the script before the user clicks Resume.
     */
    public JobFileDTO getJobFile(UUID jobId) throws Exception {
        Job job = jobService.getJob(jobId);
        Script script = jobService.getScript(jobId);
        VideoScript videoScript = objectMapper.readValue(script.getContent(), VideoScript.class);
        enrichSegments(videoScript, jobService.getAssets(jobId));
        UUID connectionId = job.getSocialConnection() != null ? job.getSocialConnection().getId() : null;
        return new JobFileDTO(jobId, job.getCreatedBy(), null, videoScript, connectionId);
    }

    /**
     * Persists user edits to scene text (or any other VideoScript field) so the
     * next resume picks up the edited copy. Asset URLs already saved to the DB
     * are preserved — only the stored script JSON is overwritten.
     */
    public JobFileDTO updateScript(UUID jobId, VideoScript edited, String requestedBy) throws Exception {
        Job job = jobService.getJob(jobId);
        jobService.updateScript(job, objectMapper.writeValueAsString(edited));
        log.info("Script updated for job {} by {}", jobId, requestedBy);
        return getJobFile(jobId);
    }

    /**
     * Lists every job created by {@code createdBy} that has a valid persisted
     * script, newest first. Jobs without a script (e.g. ChatGPT call failed
     * mid-creation) or whose stored content fails to parse are skipped so the
     * frontend never has to render half-broken rows.
     */
    public List<JobSummaryDTO> listJobs(String createdBy) {
        return jobService.listJobsByUser(createdBy).stream()
                .map(job -> {
                    VideoScript script = readScript(job.getId());
                    if (script == null) return null;
                    return new JobSummaryDTO(
                            job.getId(),
                            script.getTitle(),
                            job.getQuestion(),
                            job.getStyle(),
                            job.getStatus() != null ? job.getStatus().name() : null,
                            job.getProgress(),
                            job.getCreatedOn());
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ //
    //  Lightweight progress polling
    //
    //  These intentionally do NOT load scripts or assets — the frontend hits
    //  them on a short interval to drive the progress bar, so they need to
    //  stay cheap (a single Job row read).
    // ------------------------------------------------------------------ //

    public JobProgressDTO getJobProgress(UUID jobId) {
        return toProgress(jobService.getJob(jobId));
    }

    public List<JobProgressDTO> listJobProgress(String createdBy) {
        return jobService.listJobsByUser(createdBy).stream()
                .map(this::toProgress)
                .collect(Collectors.toList());
    }

    private JobProgressDTO toProgress(Job job) {
        return new JobProgressDTO(
                job.getId(),
                job.getStatus() != null ? job.getStatus().name() : null,
                job.getProgress(),
                derivedStage(job.getStatus(), job.getProgress()),
                job.getLastModifiedOn());
    }

    /**
     * Derives a human-readable stage label from progress %, matching the
     * milestones written by {@link JobService#updateJobProgress}:
     * <pre>
     *   0    → QUEUED
     *   1-19 → SCRIPT_GENERATION
     *   20-49→ ASSET_GENERATION
     *   50-99→ VIDEO_RENDERING
     *   100  → COMPLETED
     * </pre>
     * Returns FAILED when the job entity is in {@link Status#FAILED}.
     */
    private static String derivedStage(Status status, int progress) {
        if (status == Status.FAILED) return "FAILED";
        if (progress >= 100) return "COMPLETED";
        if (progress >= 50)  return "VIDEO_RENDERING";
        if (progress >= 20)  return "ASSET_GENERATION";
        if (progress >= 1)   return "SCRIPT_GENERATION";
        return "QUEUED";
    }

    private VideoScript readScript(UUID jobId) {
        return jobService.findScript(jobId)
                .map(Script::getContent)
                .filter(c -> !c.isBlank())
                .map(content -> {
                    try {
                        return objectMapper.readValue(content, VideoScript.class);
                    } catch (Exception e) {
                        log.warn("Skipping job {} from list — stored script JSON could not be parsed: {}",
                                jobId, e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    // ------------------------------------------------------------------ //
    //  Resume an existing job from its last known state
    // ------------------------------------------------------------------ //

    /**
     * Reconstructs the {@link JobFileDTO} from DB state, writes all known
     * asset URLs back onto the {@link VideoScript} segments, then re-queues
     * the job at the earliest incomplete pipeline stage.
     *
     * <p>Because every consumer is idempotent, a resume is safe to trigger
     * multiple times — already-completed stages are simply skipped.
     *
     * @throws IllegalStateException if the job is already COMPLETED or has no script
     */
    public JobFileDTO resumeJob(UUID jobId, String resumedBy) throws Exception {
        Job job = jobService.getJob(jobId);

        if (job.getStatus() == Status.COMPLETED) {
            throw new IllegalStateException("Job " + jobId + " is already COMPLETED.");
        }

        // Load and deserialize the stored VideoScript
        Script script = jobService.getScript(jobId);
        VideoScript videoScript = objectMapper.readValue(script.getContent(), VideoScript.class);

        // Enrich each segment with URLs already saved in the DB so that when
        // the pipeline stage receives the DTO it can skip already-done work AND
        // the JSON it forwards to the next stage already contains those URLs.
        List<Asset> assets = jobService.getAssets(jobId);
        enrichSegments(videoScript, assets);

        // Determine the correct pipeline stage to resume from
        int totalSegments = videoScript.allSegments().size();
        PipelineStage resumeStage = resolveResumeStage(jobId, assets, videoScript);

        JobFileDTO jobFileDTO = new JobFileDTO(jobId, resumedBy, null, videoScript);

        log.info("Resuming job {} at stage '{}' ({} total segments, {} assets saved)",
                jobId, resumeStage, totalSegments, assets.size());

        pipelineProducer.send(resumeStage, objectMapper.writeValueAsString(jobFileDTO));
        return jobFileDTO;
    }

    // ------------------------------------------------------------------ //
    //  Regeneration
    //
    //  The user can ask the AI to rewrite either the entire scenes array or
    //  a single scene's spoken text. Title / hook / closing are preserved
    //  unless the user edits them separately. Regenerating any scene's text
    //  invalidates that scene's downstream artifacts (voice, image, source
    //  video, rendered clip) and the final concatenated video — the next
    //  /resume rebuilds them from the new text.
    // ------------------------------------------------------------------ //

    /**
     * Re-asks the AI for a fresh scenes[] array on an existing job, keeping
     * title / hook / closing intact, then deletes every regular-scene asset
     * (voice, image, source-video, rendered clip) and the final video so the
     * next resume rebuilds them. Scene IDs are preserved (1..N).
     */
    @Transactional
    public JobFileDTO regenerateScenes(UUID jobId, String requestedBy) throws Exception {
        Job job = jobService.getJob(jobId);
        Script script = jobService.getScript(jobId);
        VideoScript current = objectMapper.readValue(script.getContent(), VideoScript.class);

        int sceneCount = current.getScenes() == null ? 8 : Math.max(1, current.getScenes().size());
        String raw = chatGPTService.regenerateScenes(
                job.getQuestion(),
                job.getStyle(),
                current.getTitle(),
                current.getHook(),
                current.getClosing(),
                sceneCount);

        List<Scene> fresh = parseScenesArray(raw);
        if (fresh.isEmpty()) {
            throw new IllegalStateException("AI returned no scenes — refusing to overwrite the existing script.");
        }
        renumberScenes(fresh);
        current.setScenes(fresh);

        Set<Integer> regeneratedIds = fresh.stream().map(Scene::getScene).collect(Collectors.toSet());
        invalidateAssetsForScenes(jobId, regeneratedIds);

        jobService.updateScript(job, objectMapper.writeValueAsString(current));
        resetJobToScriptStage(job);
        log.info("Regenerated {} scenes for job {} by {}", fresh.size(), jobId, requestedBy);
        return getJobFile(jobId);
    }

    /**
     * Re-asks the AI for fresh text for one scene only. The scene's existing
     * neighbours are passed in as flow context so the new line connects to
     * them. Only that scene's downstream assets are invalidated.
     */
    @Transactional
    public JobFileDTO regenerateScene(UUID jobId, int sceneId, String requestedBy) throws Exception {
        Job job = jobService.getJob(jobId);
        Script script = jobService.getScript(jobId);
        VideoScript current = objectMapper.readValue(script.getContent(), VideoScript.class);

        if (current.getScenes() == null) {
            throw new IllegalStateException("Job " + jobId + " has no scenes to regenerate.");
        }
        int idx = -1;
        for (int i = 0; i < current.getScenes().size(); i++) {
            if (current.getScenes().get(i).getScene() == sceneId) { idx = i; break; }
        }
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "Scene " + sceneId + " not found on job " + jobId
                            + " (only regular scenes can be regenerated, not title/hook/closing).");
        }

        String previous = idx > 0 ? current.getScenes().get(idx - 1).getText() : null;
        String next     = idx < current.getScenes().size() - 1 ? current.getScenes().get(idx + 1).getText() : null;
        String currentText = current.getScenes().get(idx).getText();

        String newText = chatGPTService.regenerateSceneText(
                job.getQuestion(),
                job.getStyle(),
                current.getTitle(),
                current.getHook(),
                current.getClosing(),
                sceneId,
                previous,
                next,
                currentText);
        newText = stripWrappingQuotes(newText);
        if (newText == null || newText.isBlank()) {
            throw new IllegalStateException("AI returned empty text for scene " + sceneId);
        }

        current.getScenes().get(idx).setText(newText);
        invalidateAssetsForScenes(jobId, Set.of(sceneId));

        jobService.updateScript(job, objectMapper.writeValueAsString(current));
        resetJobToScriptStage(job);
        log.info("Regenerated scene {} for job {} by {}", sceneId, jobId, requestedBy);
        return getJobFile(jobId);
    }

    /**
     * Parses the JSON array returned by {@link ChatGPTService#regenerateScenes}.
     * Strips the optional {@code ```json …``` } fences GPT sometimes adds even
     * when told not to, then deserialises into {@code List<Scene>}.
     */
    private List<Scene> parseScenesArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0) cleaned = cleaned.substring(firstNewline + 1);
            int closingFence = cleaned.lastIndexOf("```");
            if (closingFence >= 0) cleaned = cleaned.substring(0, closingFence);
            cleaned = cleaned.trim();
        }
        try {
            return objectMapper.readValue(cleaned, new TypeReference<List<Scene>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse regenerated scenes JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /** Reassigns 1..N to the regenerated scenes so any prior numbering quirks are normalised. */
    private static void renumberScenes(List<Scene> scenes) {
        for (int i = 0; i < scenes.size(); i++) scenes.get(i).setScene(i + 1);
    }

    /** Removes a single layer of surrounding quotes the model sometimes emits despite the rule. */
    private static String stripWrappingQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /**
     * Deletes every asset (voice, image, source video, rendered clip) bound
     * to any of the supplied scene ids, plus the final concatenated video.
     * Both the S3 object and the DB row are removed so a future resume cannot
     * accidentally pick up a stale URL.
     */
    private void invalidateAssetsForScenes(UUID jobId, Set<Integer> sceneIds) {
        if (sceneIds.isEmpty()) return;
        List<Asset> all = assetRepository.findByJobId(jobId);
        List<Asset> toDelete = new ArrayList<>();
        for (Asset a : all) {
            if (a.getMetadata() == null) continue;
            Integer sid = sceneIdFromMetadata(a);
            if (sid != null && sceneIds.contains(sid)) toDelete.add(a);
        }
        for (Asset a : toDelete) {
            try { s3StorageService.delete(a.getUrl()); }
            catch (Exception e) { log.warn("S3 delete failed for {} (continuing): {}", a.getUrl(), e.getMessage()); }
            assetRepository.delete(a);
        }
        videoRepository.findByJobId(jobId).ifPresent(video -> {
            try { s3StorageService.delete(video.getStorageUrl()); }
            catch (Exception e) { log.warn("S3 delete failed for final video {} (continuing): {}",
                    video.getStorageUrl(), e.getMessage()); }
            videoRepository.delete(video);
        });
        log.info("Invalidated {} assets across scenes {} for job {}", toDelete.size(), sceneIds, jobId);
    }

    /**
     * Extracts the scene id from an asset's metadata regardless of asset type:
     * IMAGE uses {@code "sceneId_index"}; VOICE / VIDEO_CLIP / SOURCE_VIDEO
     * use a bare {@code "sceneId"}.
     */
    private static Integer sceneIdFromMetadata(Asset a) {
        String md = a.getMetadata();
        if (md == null) return null;
        if (a.getAssetType() == AssetType.IMAGE) return parseImageSceneId(md);
        try { return Integer.parseInt(md); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Resets a job's status so the UI shows the user that downstream work
     * needs to run again. Progress drops back to the "script done, assets
     * pending" milestone (20%) and status flips to PROCESSING so the polling
     * UI re-engages once the user clicks Resume.
     */
    private void resetJobToScriptStage(Job job) {
        job.setProgress(20);
        job.setStatus(Status.PROCESSING);
        job.setLastModifiedOn(Instant.now());
        jobRepository.save(job);
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /**
     * Walks all saved assets and writes their URLs back onto the matching
     * segments so the forwarded DTO is fully populated.
     *
     * <p>Metadata key formats:
     * <ul>
     *   <li>IMAGE: {@code "sceneId_index"} (multiple per scene)</li>
     *   <li>SOURCE_VIDEO: {@code "sceneId"} (one source clip per video-mode scene)</li>
     *   <li>VOICE / VIDEO_CLIP: {@code "sceneId"}</li>
     * </ul>
     */
    private void enrichSegments(VideoScript videoScript, List<Asset> assets) {
        // Group IMAGE assets by sceneId, ordered by index, so each scene gets
        // its full ordered list of image URLs.
        Map<Integer, List<Asset>> imagesBySegment = assets.stream()
                .filter(a -> a.getAssetType() == AssetType.IMAGE && a.getMetadata() != null)
                .filter(a -> parseImageSceneId(a.getMetadata()) != null)
                .sorted(Comparator.comparingInt(a -> parseImageIndex(a.getMetadata())))
                .collect(Collectors.groupingBy(
                        a -> parseImageSceneId(a.getMetadata()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<Asset>> entry : imagesBySegment.entrySet()) {
            Scene segment = videoScript.findSegmentById(entry.getKey());
            if (segment == null) continue;
            List<String> urls = entry.getValue().stream().map(Asset::getUrl).collect(Collectors.toList());
            segment.setImageFiles(urls);
        }

        // Group SOURCE_VIDEO assets by sceneId for video-mode scenes.
        Map<Integer, List<Asset>> sourceVideosBySegment = assets.stream()
                .filter(a -> a.getAssetType() == AssetType.SOURCE_VIDEO && a.getMetadata() != null)
                .filter(a -> parseSegmentId(a.getMetadata()) != null)
                .collect(Collectors.groupingBy(
                        a -> parseSegmentId(a.getMetadata()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<Asset>> entry : sourceVideosBySegment.entrySet()) {
            Scene segment = videoScript.findSegmentById(entry.getKey());
            if (segment == null) continue;
            List<String> urls = entry.getValue().stream().map(Asset::getUrl).collect(Collectors.toList());
            segment.setSourceVideoFiles(urls);
        }

        for (Asset asset : assets) {
            if (asset.getMetadata() == null) continue;
            if (asset.getAssetType() == AssetType.IMAGE) continue; // handled above
            if (asset.getAssetType() == AssetType.SOURCE_VIDEO) continue; // handled above
            int segmentId;
            try {
                segmentId = Integer.parseInt(asset.getMetadata());
            } catch (NumberFormatException e) {
                continue;
            }

            Scene segment = videoScript.findSegmentById(segmentId);
            if (segment == null) continue;

            switch (asset.getAssetType()) {
                case VOICE      -> segment.setVoiceFile(asset.getUrl());
                case VIDEO_CLIP -> segment.setVideoFile(asset.getUrl());
            }
        }
    }

    private static Integer parseSegmentId(String metadata) {
        try {
            return Integer.parseInt(metadata);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts the scene id from IMAGE metadata. Supports both the current
     * "sceneId_index" format and the legacy bare "sceneId" format.
     */
    private static Integer parseImageSceneId(String metadata) {
        int underscore = metadata.indexOf('_');
        String sceneIdPart = underscore >= 0 ? metadata.substring(0, underscore) : metadata;
        try {
            return Integer.parseInt(sceneIdPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Extracts the trailing index from "sceneId_index"; defaults to 0 for legacy bare "sceneId". */
    private static int parseImageIndex(String metadata) {
        int underscore = metadata.indexOf('_');
        if (underscore < 0) return 0;
        try {
            return Integer.parseInt(metadata.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Finds the earliest pipeline stage that still has work to do.
     *
     * <pre>
     *  No voices yet                                         → AUDIO_GENERATION
     *  Any segment missing its mode-appropriate media        → IMAGE_GENERATION
     *  All media ready, no final video persisted             → VIDEO_COMBINE
     *  Final video exists                                    → YOUTUBE_UPLOAD
     * </pre>
     *
     * <p>The "mode-appropriate media" check matches each segment to its
     * {@link MediaMode}: image-mode scenes need ≥1 IMAGE asset, video-mode
     * scenes need ≥1 SOURCE_VIDEO asset. Mixing both in one job is supported
     * — every scene is checked against its own mode.
     */
    private PipelineStage resolveResumeStage(UUID jobId, List<Asset> assets, VideoScript videoScript) {
        int totalSegments = videoScript.allSegments().size();
        long voices = assets.stream().filter(a -> a.getAssetType() == AssetType.VOICE).count();
        boolean videoSaved = videoRepository.findByJobId(jobId).isPresent();

        if (voices < totalSegments) return PipelineStage.AUDIO_GENERATION;

        boolean allMediaReady = videoScript.allSegments().stream().allMatch(s -> hasMediaFor(s, assets));
        if (!allMediaReady)         return PipelineStage.IMAGE_GENERATION;

        if (!videoSaved)            return PipelineStage.VIDEO_COMBINE;
        return PipelineStage.YOUTUBE_UPLOAD;
    }

    /**
     * Whether the given segment already has at least one asset of the type its
     * {@link MediaMode} requires. Image-mode segments need any IMAGE row whose
     * metadata starts with the segment id; video-mode segments need a
     * SOURCE_VIDEO row whose metadata equals the segment id.
     */
    private static boolean hasMediaFor(Scene segment, List<Asset> assets) {
        int sid = segment.getScene();
        MediaMode mode = segment.getMediaMode() != null ? segment.getMediaMode() : MediaMode.IMAGES;
        if (mode == MediaMode.VIDEO_CLIP) {
            return assets.stream().anyMatch(a ->
                    a.getAssetType() == AssetType.SOURCE_VIDEO
                            && String.valueOf(sid).equals(a.getMetadata()));
        }
        return assets.stream().anyMatch(a -> {
            if (a.getAssetType() != AssetType.IMAGE || a.getMetadata() == null) return false;
            Integer scene = parseImageSceneId(a.getMetadata());
            return scene != null && scene == sid;
        });
    }
}
