package com.m3rwallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.consensus")
@Data
public class ConsensusProperties {
    private boolean enabled = false;
    private List<String> peers = new ArrayList<>();
    private long timeoutMs = 2500;
    private String sharedSecret = "";
}
