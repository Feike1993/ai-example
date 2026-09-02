package com.feike.ai.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP Bearer Filter：无/错 token 401；正确放行。
 */
@DisplayName("McpBearerAuthFilter")
class McpBearerAuthFilterTest {

    @Test
    void matchesBearerCaseInsensitivePrefix() {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("dev-mcp-token");
        assertTrue(filter.matchesBearer("Bearer dev-mcp-token"));
        assertTrue(filter.matchesBearer("bearer dev-mcp-token"));
        assertFalse(filter.matchesBearer("Bearer wrong"));
        assertFalse(filter.matchesBearer("Basic x"));
        assertFalse(filter.matchesBearer(""));
    }

    @Test
    void rejectsMissingAuthorizationOnMcpPath() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("dev-mcp-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals(null, chain.getRequest());
    }

    @Test
    void rejectsWrongToken() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("dev-mcp-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals(null, chain.getRequest());
    }

    @Test
    void acceptsValidBearerAndContinues() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("dev-mcp-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer dev-mcp-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
    }

    @Test
    void skipsNonMcpPaths() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("dev-mcp-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
    }
}
