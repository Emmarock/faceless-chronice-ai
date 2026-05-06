package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // tolerate legacy persisted scripts (e.g. old "imageFile" field)
public class Scene {
    private int scene;
    private String text;
    private List<String> imageFiles = new ArrayList<>(); // filled after AI image generation (multiple per scene)
    /**
     * Source video clip URL(s) used when {@link #mediaMode} is
     * {@link MediaMode#VIDEO_CLIP}. Parallel to {@link #imageFiles} — kept
     * separately so a user can flip a scene's mode back and forth without
     * losing the other side's work.
     */
    private List<String> sourceVideoFiles = new ArrayList<>();
    private String voiceFile; // filled after TTS
    private String videoFile; // filled after FFmpeg assembly
    /**
     * Narration length in seconds, set by the audio stage from ffprobe.
     * Downstream stages use this to scale image count per scene so every
     * shot lasts a few seconds rather than minutes (longer narration ⇒
     * more images ⇒ more visual variety in the final clip).
     */
    private Double durationSeconds;
    /**
     * Defaults to {@link MediaMode#IMAGES} so jobs created before this field
     * existed (and any caller that omits it from JSON) keep the legacy
     * image-montage behaviour.
     */
    private MediaMode mediaMode = MediaMode.IMAGES;
}