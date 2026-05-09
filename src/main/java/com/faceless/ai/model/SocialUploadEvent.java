package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;

import java.util.Set;
import java.util.UUID;

public record SocialUploadEvent(UUID videoId, Set<SocialPlatform> platforms) {}