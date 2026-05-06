package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoScript {

    // Reserved segment IDs — kept negative / > 999 so they never clash with
    // scene numbers (which start at 1) and sort in the correct playback order.
    public static final int SEGMENT_TITLE   = -2;
    public static final int SEGMENT_HOOK    = -1;
    // regular scenes: 1 … N
    public static final int SEGMENT_CLOSING = 1000;

    private String title;
    private String hook;
    private List<Scene> scenes;
    private String closing;

    // ------------------------------------------------------------------ //
    //  Lazy-init Scene wrappers for non-scene segments.
    //
    //  These are null in the first JSON produced by ChatGPT (which only has
    //  the plain strings above).  On the first call to allSegments() they are
    //  created from the string text.  From that point on the same objects are
    //  reused, so each pipeline stage can write back imageFile / voiceFile /
    //  videoFile and the updates survive the next objectMapper serialisation.
    // ------------------------------------------------------------------ //

    private Scene titleScene;
    private Scene hookScene;
    private Scene closingScene;

    /**
     * Returns every speakable segment in playback order:
     *   title → hook → scene 1 … N → closing
     *
     * Non-scene segments are wrapped as {@link Scene} objects with reserved
     * {@code scene} ids so every downstream consumer treats them uniformly.
     * The wrapper objects are lazily initialised and then reused, which means
     * any {@code setVoiceFile / setImageFile / setVideoFile} calls made by
     * consumers survive the next JSON serialisation of the enclosing
     * {@link com.faceless.ai.model.JobFileDTO}.
     */
    public List<Scene> allSegments() {
        List<Scene> segments = new ArrayList<>();

        if (title != null && !title.isBlank()) {
            if (titleScene == null) titleScene = makeScene(SEGMENT_TITLE, title);
            segments.add(titleScene);
        }
        if (hook != null && !hook.isBlank()) {
            if (hookScene == null) hookScene = makeScene(SEGMENT_HOOK, hook);
            segments.add(hookScene);
        }
        if (scenes != null) segments.addAll(scenes);
        if (closing != null && !closing.isBlank()) {
            if (closingScene == null) closingScene = makeScene(SEGMENT_CLOSING, closing);
            segments.add(closingScene);
        }

        return segments;
    }

    /**
     * Looks up a segment by its numeric id across all parts of the script.
     * Returns {@code null} when the id does not match any segment.
     */
    public Scene findSegmentById(int id) {
        return allSegments().stream()
                .filter(s -> s.getScene() == id)
                .findFirst()
                .orElse(null);
    }

    private Scene makeScene(int id, String text) {
        Scene s = new Scene();
        s.setScene(id);
        s.setText(text);
        return s;
    }
}