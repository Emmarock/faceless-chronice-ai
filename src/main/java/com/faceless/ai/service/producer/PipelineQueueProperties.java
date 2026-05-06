package com.faceless.ai.service.producer;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Holds the SQS queue URL (or name — Spring Cloud AWS resolves either) for
 * each pipeline stage. Bound from {@code chronicleai.queue.*} in
 * application.yaml; in production every value is overridden by a
 * {@code QUEUE_*_URL} env var injected from Terraform.
 *
 * Kept as a separate type rather than 6 individual {@code @Value} fields so
 * @SqsListener annotations can refer to them with a single SpEL expression
 * and {@link PipelineProducer} can route by {@link PipelineStage}.
 */
@Configuration
@ConfigurationProperties(prefix = "chronicleai.queue")
@Getter
@Setter
public class PipelineQueueProperties {

    private String audioGeneration;
    private String imageGeneration;
    private String videoCombine;
    private String youtubeUpload;
    private String tiktokUpload;
    private String twitterUpload;
    private String facebookUpload;

    public String urlFor(PipelineStage stage) {
        return switch (stage) {
            case AUDIO_GENERATION -> audioGeneration;
            case IMAGE_GENERATION -> imageGeneration;
            case VIDEO_COMBINE    -> videoCombine;
            case YOUTUBE_UPLOAD   -> youtubeUpload;
            case TIKTOK_UPLOAD    -> tiktokUpload;
            case TWITTER_UPLOAD   -> twitterUpload;
            case FACEBOOK_UPLOAD  -> facebookUpload;
        };
    }
}