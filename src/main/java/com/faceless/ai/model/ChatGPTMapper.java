package com.faceless.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ChatGPTMapper {

    private final ObjectMapper objectMapper;

    public VideoScript mapToVideoScript(String chatGptResponseJson) throws Exception {
        // Parse the top-level ChatGPT response
        JsonNode root = objectMapper.readTree(chatGptResponseJson);

        // Navigate to choices[0].message.content
        JsonNode contentNode = root
                .path("choices")
                .get(0)
                .path("message")
                .path("content");

        if (contentNode.isMissingNode()) {
            throw new IllegalArgumentException("Invalid ChatGPT response: content not found");
        }

        // contentNode is a JSON string, so parse it again
        String contentString = contentNode.asText();
        return objectMapper.readValue(contentString, VideoScript.class);
    }
}