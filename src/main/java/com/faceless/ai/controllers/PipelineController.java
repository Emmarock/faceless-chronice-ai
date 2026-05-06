package com.faceless.ai.controllers;

import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineProducer pipelineProducer;

    @PostMapping("/run")
    public ResponseEntity<String> runPipeline(@RequestParam("jobFile") MultipartFile jobFile) throws Exception {
        Path tempFile = Files.createTempFile("job-", ".json");
        jobFile.transferTo(tempFile.toFile());

        String payload = Files.readString(tempFile);
        pipelineProducer.send(PipelineStage.AUDIO_GENERATION, payload);

        return ResponseEntity.accepted().body("Pipeline started — processing via SQS consumers.");
    }
}