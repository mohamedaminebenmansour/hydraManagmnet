package tn.hydra.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /api/auth/login through the real controller: the 6th failure for one email in 15 min → 429. */
@SpringBootTest
class LoginRateLimitDbTest {

    @Autowired private AuthController controller;

    @Test
    void sixthFailedLoginForTheSameEmail_returns429() {
        String email = "inconnu." + UUID.randomUUID() + "@hydra.test"; // no such user: every attempt fails
        for (int i = 0; i < 5; i++) {
            ResponseEntity<?> res = controller.login(new AuthController.LoginRequest(email, "mauvais"),
                    request(), new MockHttpServletResponse());
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<?> sixth = controller.login(new AuthController.LoginRequest(email, "mauvais"),
                request(), new MockHttpServletResponse());

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(((Map<?, ?>) sixth.getBody()).get("message"))
                .isEqualTo("Trop de tentatives. Réessayez dans 15 minutes.");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/login");
        r.setRemoteAddr("203.0.113." + (int) (Math.random() * 200)); // varying IPs: the email limit is what trips
        return r;
    }
}
