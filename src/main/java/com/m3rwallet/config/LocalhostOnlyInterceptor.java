package com.m3rwallet.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Slf4j
public class LocalhostOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        
        // Only apply localhost check to dashboard routes
        if (requestURI.equals("/") || requestURI.startsWith("/admin")) {
            String remoteAddr = request.getRemoteAddr();
            
            // Check if the request is from localhost
            if (!isLocalhost(remoteAddr)) {
                log.warn("[ADMIN ACCESS DENIED] Unauthorized access attempt from IP: {}", remoteAddr);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"error\": \"Node dashboard is only accessible from localhost\"}");
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
}
