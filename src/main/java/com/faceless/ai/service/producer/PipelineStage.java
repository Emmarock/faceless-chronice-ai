package com.faceless.ai.service.producer;

/**
 * Logical pipeline stage. Maps 1:1 onto an SQS queue declared in Terraform
 * and a property under {@code chronicleai.queue.*}.
 */
public enum PipelineStage {
    AUDIO_GENERATION,
    IMAGE_GENERATION,
    VIDEO_COMBINE,
    YOUTUBE_UPLOAD,
    TIKTOK_UPLOAD,
    TWITTER_UPLOAD,
    FACEBOOK_UPLOAD;
}