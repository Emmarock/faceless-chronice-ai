package com.faceless.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class BeanConfigs {

    @Bean
    public ObjectMapper objectMapper() {
        // Serialize Instant/LocalDateTime as ISO-8601 strings so JS `new Date(...)`
        // parses them correctly. Default Jackson behaviour is epoch-seconds-with-nanos,
        // which JS misreads as milliseconds and renders as 1970.
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}