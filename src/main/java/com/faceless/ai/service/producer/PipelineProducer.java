package com.faceless.ai.service.producer;

import com.faceless.ai.model.SocialUploadEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends a JSON payload to the SQS queue backing a given {@link PipelineStage}.
 * Single chokepoint so consumers / services don't need to know queue URLs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineProducer {

    private final SqsTemplate sqsTemplate;
    private final PipelineQueueProperties queues;

    public void send(PipelineStage stage, String payload) {
        String queue = queues.urlFor(stage);
        if (queue == null || queue.isBlank()) {
            throw new IllegalStateException("No queue configured for stage " + stage
                    + " — check chronicleai.queue.* in application.yaml.");
        }
        log.debug("Publishing {} payload to {}", stage, queue);
        sqsTemplate.send(queue, payload);
    }
    public void publishVideo(PipelineStage stage, SocialUploadEvent payload) {
        String queue = queues.urlFor(stage);
        if (queue == null || queue.isBlank()) {
            throw new IllegalStateException("No queue configured for stage " + stage
                    + " — check chronicleai.queue.* in application.yaml.");
        }
        log.debug("Publishing {} payload to {}", stage, queue);
        sqsTemplate.send(queue, payload);
    }
}