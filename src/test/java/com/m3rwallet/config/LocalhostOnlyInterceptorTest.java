package com.m3rwallet.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class LocalhostOnlyInterceptorTest {

    private final LocalhostOnlyInterceptor interceptor = new LocalhostOnlyInterceptor();

    @Test
    void rootPathIsPublic() throws Exception {
        MockHttpServletRequest request = request("/", "203.0.113.10", "public.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void adminAllowsLocalhostRemoteAndHost() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "localhost:3000");
        addAdminAuth(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void adminRejectsMissingCredentials() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("WWW-Authenticate")).contains("M3R Admin");
    }

    @Test
    void adminRejectsWrongCredentials() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "localhost:3000");
        addBasicAuth(request, "hululuadmin", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void adminRejectsTunnelHostEvenWhenRemoteIsLoopback() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "m3r-node.example-tunnel.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("Admin dashboard is only accessible from localhost");
    }

    @Test
    void adminRejectsForwardedTunnelHost() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "localhost:3000");
        request.addHeader("X-Forwarded-Host", "m3r-node.example-tunnel.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void adminRejectsForwardedRemoteClient() throws Exception {
        MockHttpServletRequest request = request("/admin", "127.0.0.1", "localhost:3000");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void adminRejectsNonLocalRemoteAddress() throws Exception {
        MockHttpServletRequest request = request("/admin", "203.0.113.10", "localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    private MockHttpServletRequest request(String uri, String remoteAddr, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        request.addHeader("Host", host);
        return request;
    }

    private void addAdminAuth(MockHttpServletRequest request) {
        addBasicAuth(request, "hululuadmin", "puripuri saitama");
    }

    private void addBasicAuth(MockHttpServletRequest request, String username, String password) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Basic " + encoded);
    }
}
