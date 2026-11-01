package tn.hydra.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-safety settings, checked through the real Spring Security filter chain
 * (chore/security-hardening). Default HYDRA_ALLOWED_ORIGINS = http://localhost:4200.
 */
@SpringBootTest
class SecurityHardeningTest {

    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityChain;

    private MockHttpServletResponse get(String uri, String origin) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (origin != null) req.addHeader("Origin", origin);
        MockHttpServletResponse res = new MockHttpServletResponse();
        securityChain.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void aRequestFromANonAllowedOrigin_getsNoCorsHeaders() throws Exception {
        MockHttpServletResponse res = get("/health", "https://evil.example");

        assertThat(res.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        MockHttpServletResponse res = get("/health", null);

        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    void anAllowedOrigin_getsItsOwnOriginBack_neverAWildcard() throws Exception {
        MockHttpServletResponse res = get("/health", "http://localhost:4200");

        assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4200");
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }
}
