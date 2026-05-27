package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-platform overrides the user supplied in PublishModal. All fields are
 * optional — null / blank means "fall back to the underlying video's
 * title/description".
 *
 * <p>Carried on {@link VideoPublishRequest#getOverrides()} and persisted on
 * the matching {@code SocialUpload} row so the upload consumer can read it
 * back at publish time (including for scheduled publishes that fire hours
 * or days after the user picked their captions).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformPostOptions {
    private String title;
    private String caption;
    private List<String> hashtags;
}