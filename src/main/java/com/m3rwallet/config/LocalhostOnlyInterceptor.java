package com.m3rwallet.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
@Slf4j
public class LocalhostOnlyInterceptor implements HandlerInterceptor {
    @Value("${app.admin.username:hululuadmin}")
    private String adminUsername = "hululuadmin";

    @Value("${app.admin.password:puripuri saitama}")
    private String adminPassword = "puripuri saitama";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        
        // Admin routes are intentionally local-machine only.
        if (requestURI.startsWith("/admin")) {
            String remoteAddr = request.getRemoteAddr();
            String host = request.getHeader("Host");
            String forwardedHost = request.getHeader("X-Forwarded-Host");
            String forwardedFor = request.getHeader("X-Forwarded-For");
            
            if (!isLocalhost(remoteAddr)
                    || !isLocalHostHeader(host)
                    || !isLocalOrMissingHostHeader(forwardedHost)
                    || !isLocalOrMissingForwardedFor(forwardedFor)) {
                log.warn("[ADMIN ACCESS DENIED] Unauthorized access attempt from IP: {} host: {} forwardedHost: {} forwardedFor: {}",
                        remoteAddr, host, forwardedHost, forwardedFor);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"error\": \"Admin dashboard is only accessible from localhost\"}");
                return false;
            }
            if (!hasValidAdminCredentials(request)) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"M3R Admin\", charset=\"UTF-8\"");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"error\": \"Admin authentication required\"}");
                return false;
            }
            log.info("[ADMIN ACCESS] Allowed from: {}", remoteAddr);
        }
        
        return true;
    }

    /**
     * Check if the given IP is localhost
     */
    private boolean isLocalhost(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || 
                             ip.equals("localhost") || 
                             ip.equals("0:0:0:0:0:0:0:1") ||
                             ip.startsWith("127.") ||
                             ip.startsWith("::1"));
    }

    private boolean isLocalHostHeader(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim().toLowerCase();
        int portIndex = normalized.lastIndexOf(':');
        if (portIndex > -1 && !normalized.endsWith("]")) {
            normalized = normalized.substring(0, portIndex);
        }
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.startsWith("127.")
                || normalized.equals("[::1]")
                || normalized.equals("::1");
    }

    private boolean isLocalOrMissingHostHeader(String host) {
        return host == null || host.isBlank() || isLocalHostHeader(host);
    }

    private boolean isLocalOrMissingForwardedFor(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return true;
        }
        String firstAddress = forwardedFor.split(",")[0].trim();
        return isLocalhost(firstAddress);
    }

    private boolean hasValidAdminCredentials(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        try {
            String encodedCredentials = authorization.substring("Basic ".length()).trim();
            String credentials = new String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator < 0) {
                return false;
            }
            String username = credentials.substring(0, separator);
            String password = credentials.substring(separator + 1);
            return constantTimeEquals(username, adminUsername) && constantTimeEquals(password, adminPassword);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
