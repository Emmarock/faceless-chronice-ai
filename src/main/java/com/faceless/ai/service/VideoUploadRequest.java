package com.faceless.ai.service;

import java.nio.file.Path;

public record VideoUploadRequest(String userId, Path videoFile, String title, String description) {
}