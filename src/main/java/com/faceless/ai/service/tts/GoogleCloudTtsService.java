package com.faceless.ai.service.tts;

import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.service.VideoPipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chronicleai.tts.provider", havingValue = "google")
public class GoogleCloudTtsService implements TtsService {

    private static final String BASE_URL = "https://texttospeech.googleapis.com";

    private final ObjectMapper objectMapper;

    @Value("${chronicleai.google-tts.api-key}")
    private String apiKey;

    @Value("${chronicleai.google-tts.language-code:en-US}")
    private String languageCode;

    @Value("${chronicleai.google-tts.voice:en-US-Neural2-D}")
    private String voice;

    @Value("${chronicleai.google-tts.audio-encoding:MP3}")
    private String audioEncoding;

    @Override
    public Path generateVoice(String text, String jobId, int sceneId) throws Exception {
        log.info("Generating voice via Google Cloud TTS for job {} scene {} (voice={}, encoding={})",
                jobId, sceneId, voice, audioEncoding);

        WebClient client = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                        .build())
                .build();

        String responseJson = client.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/text:synthesize")
                        .queryParam("key", apiKey)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "input",       Map.of("text", text),
                        "voice",       Map.of(
                                "languageCode", languageCode,
                                "name",         voice
                        ),
                        "audioConfig", Map.of("audioEncoding", audioEncoding)
                ))
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp -> resp.bodyToMono(String.class)
                                .map(err -> new ExternalApiException(
                                        "Google Cloud TTS failed [" + resp.statusCode() + "]: " + err))
                )
                .bodyToMono(String.class)
                .blockOptional()
                .orElseThrow(() -> new ExternalApiException("Google Cloud TTS returned empty response"));

        JsonNode root = objectMapper.readTree(responseJson);
        String audioContent = root.path("audioContent").asText(null);
        if (audioContent == null || audioContent.isEmpty()) {
            throw new ExternalApiException("Google Cloud TTS response missing audioContent: " + responseJson);
        }
        byte[] audioBytes = Base64.getDecoder().decode(audioContent);

        Path output = Paths.get(VideoPipelineService.VOICE_DIR,
                "voice_" + jobId + "_scene_" + sceneId + "." + extensionFor(audioEncoding));
        Files.write(output, audioBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Audio saved to {}", output);
        return output;
    }

    private static String extensionFor(String encoding) {
        return switch (encoding.toUpperCase()) {
            case "MP3"                  -> "mp3";
            case "OGG_OPUS"             -> "ogg";
            case "MULAW", "ALAW", "PCM",
                 "LINEAR16"             -> "wav";
            default                      -> "audio";
        };
    }
}