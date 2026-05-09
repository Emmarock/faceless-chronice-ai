package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoPublishRequest {
    private Set<SocialPlatform> platforms;
}