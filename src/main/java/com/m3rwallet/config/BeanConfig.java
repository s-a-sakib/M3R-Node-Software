package com.m3rwallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.m3rwallet.config.ConsensusProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class BeanConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, ConsensusProperties consensusProperties) {
        Duration timeout = Duration.ofMillis(consensusProperties.getTimeoutMs());
        return builder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
    }
}
