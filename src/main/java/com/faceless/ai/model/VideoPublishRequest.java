package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoPublishRequest {
    private List<SocialPlatform> platforms;
}