package com.faceless.ai.service;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.Twin;
import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.TwinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Onboarding + lifecycle for AI tutor twins. Creating a twin uploads the user's
 * clip to HeyGen, kicks off avatar/voice training, and debits the
 * {@link LedgerKind#DEBIT_TWIN_TRAINING} cost. {@code TwinLessonPoller} then
 * advances the row to COMPLETED as training finishes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TwinService {

    private final TwinRepository twinRepository;
    private final HeyGenService heyGenService;
    private final S3StorageService s3StorageService;
    private final SubscriptionService subscriptionService;
    private final AppUserRepository appUserRepository;
    private final BillingProperties billingProperties;

    @org.springframework.beans.factory.annotation.Value("${chronicleai.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Transactional
    public Twin createTwin(String userId, String name, MultipartFile videoFile, MultipartFile audioFile)
            throws Exception {
        if (!heyGenService.isConfigured()) {
            throw new ExternalApiException(
                    "AI Tutor is not configured — set HEYGEN_API_KEY to enable twin creation.");
        }
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("A source video or photo is required to create a twin.");
        }
        String twinName = (name == null || name.isBlank()) ? "My teaching twin" : name.trim();

        // Fail fast on out-of-credits before any upload/HeyGen work.
        Subscription subscription = resolveSubscription(userId);

        // Stash the clip in S3 (our record + UI playback of the source) and
        // create the row before debiting so the ledger can reference the id.
        Path temp = Files.createTempFile("twin-src-", suffixFor(videoFile, ".mp4"));
        try {
            videoFile.transferTo(temp.toFile());
            String key = "twins/" + userId + "/" + UUID.randomUUID() + suffixFor(videoFile, ".mp4");
            String sourceUrl = s3StorageService.upload(temp, key);

            Twin twin = twinRepository.save(Twin.builder()
                    .userId(userId)
                    .name(twinName)
                    .sourceVideoUrl(sourceUrl)
                    .status(Status.QUEUED)
                    .createdBy(userId)
                    .lastModifiedBy(userId)
                    .createdOn(Instant.now())
                    .lastModifiedOn(Instant.now())
                    .build());

            subscriptionService.consume(subscription, LedgerKind.DEBIT_TWIN_TRAINING,
                    "Twin training: " + twinName, twin.getId());

            // Talking-photo onboarding: extract a still frame from the clip
            // (or use the upload directly if it's already an image) and create
            // an instantly-usable talking photo on HeyGen. There is no async
            // training, so the twin is COMPLETED on success. On failure, mark
            // FAILED and refund so the user isn't charged for an unusable twin.
            Path image = null;
            try {
                image = ensureImage(temp, videoFile.getContentType());
                String talkingPhotoId = heyGenService.uploadTalkingPhoto(image);
                twin.setHeygenAvatarId(talkingPhotoId);

                // Best-effort voice clone. Prefer an explicitly uploaded audio
                // sample (the "photo + voice" path); otherwise fall back to the
                // video's own audio track. Non-fatal: any failure — or a
                // photo-only upload with no audio — leaves the twin on the
                // configured default voice rather than failing the whole twin.
                String voiceId = (audioFile != null && !audioFile.isEmpty())
                        ? tryCloneVoiceFromUpload(audioFile, twinName)
                        : tryCloneVoice(temp, videoFile.getContentType(), twinName);
                if (voiceId != null) twin.setHeygenVoiceId(voiceId);

                twin.setStatus(Status.COMPLETED);
                twin.setLastModifiedOn(Instant.now());
                return twinRepository.save(twin);
            } catch (Exception e) {
                log.error("HeyGen talking-photo creation failed for twin {}: {}", twin.getId(), e.getMessage());
                twin.setStatus(Status.FAILED);
                twin.setErrorMessage(truncate(e.getMessage()));
                twin.setLastModifiedOn(Instant.now());
                twinRepository.save(twin);
                refund(subscription, LedgerKind.DEBIT_TWIN_TRAINING, "Refund: twin creation failed");
                throw new ExternalApiException("Could not create twin: " + e.getMessage(), e);
            } finally {
                if (image != null && !image.equals(temp)) Files.deleteIfExists(image);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Returns an image path for the talking-photo upload: the source itself
     * when the upload is already an image, otherwise a single frame extracted
     * from the video via ffmpeg.
     */
    private Path ensureImage(Path source, String contentType) throws Exception {
        if (isImage(contentType, source)) return source;
        return extractFrame(source);
    }

    /**
     * Attempts to clone the speaker's voice from the clip's audio. Returns the
     * HeyGen voice id, or null when there's no audio (photo upload) or cloning
     * isn't available — callers then keep the default voice.
     */
    private String tryCloneVoice(Path source, String contentType, String name) {
        if (isImage(contentType, source)) return null; // a photo has no audio to clone
        Path audio = null;
        try {
            audio = extractAudio(source);
            return heyGenService.cloneVoice(audio, name + " voice");
        } catch (Exception e) {
            log.warn("Voice clone skipped/failed for '{}': {} — using the default voice.", name, e.getMessage());
            return null;
        } finally {
            if (audio != null) {
                try {
                    Files.deleteIfExists(audio);
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Clones the voice from an explicitly uploaded audio sample (the
     * "photo + voice" path). Non-fatal — returns null on any failure so the
     * twin keeps the default voice.
     */
    private String tryCloneVoiceFromUpload(MultipartFile audioFile, String name) {
        Path temp = null;
        try {
            temp = Files.createTempFile("twin-voice-", suffixFor(audioFile, ".mp3"));
            audioFile.transferTo(temp.toFile());
            return heyGenService.cloneVoice(temp, name + " voice");
        } catch (Exception e) {
            log.warn("Voice clone from uploaded audio failed for '{}': {} — using the default voice.", name, e.getMessage());
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    /** Extracts a mono MP3 audio track from the clip for voice cloning. */
    private Path extractAudio(Path video) throws Exception {
        Path out = Files.createTempFile("twin-audio-", ".mp3");
        String[] cmd = {
                ffmpegPath, "-y", "-i", video.toString(),
                "-vn", "-ac", "1", "-ar", "44100", "-b:a", "128k", out.toString()
        };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0 || sizeOrZero(out) == 0) {
            throw new ExternalApiException("Could not extract an audio track from the clip (no sound?).");
        }
        return out;
    }

    /** Grabs one frame ~1s in (avoids a black opening frame); falls back to the first frame. */
    private Path extractFrame(Path video) throws Exception {
        Path out = Files.createTempFile("twin-frame-", ".jpg");
        if (!runFfmpegFrame(video, out, "1") || sizeOrZero(out) == 0) {
            if (!runFfmpegFrame(video, out, "0") || sizeOrZero(out) == 0) {
                throw new ExternalApiException(
                        "Could not extract a usable frame from the clip — try a different recording or upload a photo.");
            }
        }
        return out;
    }

    private boolean runFfmpegFrame(Path video, Path out, String seekSeconds) throws Exception {
        String[] cmd = {
                ffmpegPath, "-y", "-ss", seekSeconds, "-i", video.toString(),
                "-frames:v", "1", "-q:v", "2", out.toString()
        };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); // drain so the process can't block on a full pipe
        return p.waitFor() == 0;
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isImage(String contentType, Path file) {
        if (contentType != null && contentType.toLowerCase().startsWith("image")) return true;
        String n = file.getFileName().toString().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    public List<Twin> listForUser(String userId) {
        return twinRepository.findByUserIdOrderByCreatedOnDesc(userId);
    }

    public Twin getForUser(UUID id, String userId) {
        return twinRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Twin not found: " + id));
    }

    @Transactional
    public void deleteForUser(UUID id, String userId) {
        Twin twin = getForUser(id, userId);
        twinRepository.delete(twin);
    }

    // ------------------------------------------------------------------ //

    private Subscription resolveSubscription(String userId) {
        AppUser user = appUserRepository.findByExternalId(userId)
                .orElseGet(() -> appUserRepository.save(AppUser.builder()
                        .externalId(userId)
                        .createdBy(userId)
                        .lastModifiedBy(userId)
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build()));
        return subscriptionService.getOrCreateForUser(user);
    }

    private void refund(Subscription subscription, LedgerKind debitKind, String memo) {
        int cost = billingProperties.costFor(debitKind);
        if (cost > 0) {
            subscriptionService.grantCredits(subscription, cost, LedgerKind.REFUND, memo, null);
        }
    }

    private static String suffixFor(MultipartFile file, String fallback) {
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) return name.substring(dot);
        }
        return fallback;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1900 ? s.substring(0, 1900) : s;
    }
}
