package com.faceless.ai.service.tts;

import java.nio.file.Path;

public interface TtsService {
    Path generateVoice(String text, String jobId, int sceneId) throws Exception;
}
