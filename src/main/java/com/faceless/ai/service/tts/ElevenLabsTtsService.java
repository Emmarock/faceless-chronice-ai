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
@ConditionalOnProperty(name = "chronicleai.tts.provider", havingValue = "elevenlabs")
public class ElevenLabsTtsService implements TtsService {

    private static final String BASE_URL = "https://api.elevenlabs.io";

    @Value("${chronicleai.elevenlabs.api-key}")
    private String apiKey;

    @Value("${chronicleai.elevenlabs.voice}")
    private String voiceId;

    @Value("${chronicleai.elevenlabs.model:eleven_multilingual_v2}")
    private String model;

    @Override
    public Path generateVoice(String text, String jobId, int sceneId) throws Exception {
        log.info("Generating voice via ElevenLabs for job {} scene {}", jobId, sceneId);

        Map<String, Object> body = Map.of(
                "text",     text,
                "model_id", model,
                "voice_settings", Map.of(
                        "stability",         0.75,
                        "similarity_boost",  0.90,
                        "style",             0.20,
                        "use_speaker_boost", true
                )
        );

        WebClient client = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                        .build())
                .build();

        byte[] audioBytes = client.post()
                .uri("/v1/text-to-speech/{voiceId}", voiceId)
                .header("xi-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp -> resp.bodyToMono(String.class)
                                .map(err -> new ExternalApiException(
                                        "ElevenLabs TTS failed [" + resp.statusCode() + "]: " + err))
                )
                .bodyToMono(byte[].class)
                .blockOptional()
                .orElseThrow(() -> new ExternalApiException("ElevenLabs returned empty audio"));

        Path output = Paths.get(VideoPipelineService.VOICE_DIR,
                "voice_" + jobId + "_scene_" + sceneId + ".mp3");
        Files.write(output, audioBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Audio saved to {}", output);
        return output;
    }
}