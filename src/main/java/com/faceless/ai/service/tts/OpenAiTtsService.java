package com.faceless.ai.service.tts;

import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.service.VideoPipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.tts.provider", havingValue = "openai")
public class OpenAiTtsService implements TtsService {

    private static final String BASE_URL = "https://api.openai.com";

    @Value("${chronicleai.openai.api-key}")
    private String apiKey;

    @Value("${chronicleai.openai.tts-model:gpt-4o-mini-tts}")
    private String model;

    @Value("${chronicleai.openai.tts-voice:alloy}")
    private String voice;

    @Value("${chronicleai.openai.tts-format:mp3}")
    private String format;

    @Override
    public Path generateVoice(String text, String jobId, int sceneId) throws Exception {
        log.info("Generating voice via OpenAI for job {} scene {} (model={}, voice={})",
                jobId, sceneId, model, voice);

        WebClient client = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                        .build())
                .build();

        byte[] audioBytes = client.post()
                .uri("/v1/audio/speech")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model",           model,
                        "voice",           voice,
                        "input",           text,
                        "response_format", format
                ))
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp -> resp.bodyToMono(String.class)
                                .map(err -> new ExternalApiException(
                                        "OpenAI TTS failed [" + resp.statusCode() + "]: " + err))
                )
                .bodyToMono(byte[].class)
                .blockOptional()
                .orElseThrow(() -> new ExternalApiException("OpenAI TTS returned empty audio"));

        Path output = Paths.get(VideoPipelineService.VOICE_DIR,
                "voice_" + jobId + "_scene_" + sceneId + "." + format);
        Files.write(output, audioBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Audio saved to {}", output);
        return output;
    }
}