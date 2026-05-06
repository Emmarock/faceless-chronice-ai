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
@ConditionalOnProperty(name = "chronicleai.tts.provider", havingValue = "yarngpt")
public class YarnGptTtsService implements TtsService {

    @Value("${chronicleai.yarngpt-tts.url:http://localhost:5004}")
    private String yarngptUrl;

    @Value("${chronicleai.yarngpt-tts.lang:yoruba}")
    private String lang;

    @Value("${chronicleai.yarngpt-tts.speaker:yoruba_male2}")
    private String speaker;

    @Override
    public Path generateVoice(String text, String jobId, int sceneId) throws Exception {
        log.info("Generating voice via YarnGPT for job {} scene {} (lang={}, speaker={})",
                jobId, sceneId, lang, speaker);

        WebClient client = WebClient.builder()
                .baseUrl(yarngptUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                        .build())
                .build();

        byte[] audioBytes = client.post()
                .uri("/tts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "text", text,
                        "lang", lang,
                        "speaker", speaker
                ))
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp -> resp.bodyToMono(String.class)
                                .map(err -> new ExternalApiException(
                                        "YarnGPT TTS failed [" + resp.statusCode() + "]: " + err))
                )
                .bodyToMono(byte[].class)
                .blockOptional()
                .orElseThrow(() -> new ExternalApiException("YarnGPT TTS returned empty audio"));

        Path output = Paths.get(VideoPipelineService.VOICE_DIR,
                "voice_" + jobId + "_scene_" + sceneId + ".wav");
        Files.write(output, audioBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Audio saved to {}", output);
        return output;
    }
}