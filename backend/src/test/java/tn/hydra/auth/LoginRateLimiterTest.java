package tn.hydra.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** LoginRateLimiter: 5 failures per email / 20 per IP in 15 minutes. No Spring, no database. */
class LoginRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    };
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void fiveFailuresForAnEmail_blockTheSixthAttempt_untilTheWindowPasses() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.isBlocked("Chef@Hydra.tn", "10.0.0." + i)).isFalse();
            limiter.recordFailure("chef@hydra.tn", "10.0.0." + i);
        }
        assertThat(limiter.isBlocked("chef@hydra.tn", "10.0.0.99")).isTrue(); // same email, other IP
        assertThat(limiter.isBlocked("autre@hydra.tn", "10.0.0.99")).isFalse();

        now.addAndGet(LoginRateLimiter.WINDOW_MS);
        assertThat(limiter.isBlocked("chef@hydra.tn", "10.0.0.99")).isFalse();
    }

    @Test
    void twentyFailuresFromAnIp_blockThatIp_forEveryEmail() {
        for (int i = 0; i < 20; i++) limiter.recordFailure("user" + i + "@hydra.tn", "196.0.0.1");
        assertThat(limiter.isBlocked("nouveau@hydra.tn", "196.0.0.1")).isTrue();
        assertThat(limiter.isBlocked("nouveau@hydra.tn", "196.0.0.2")).isFalse();
    }

    @Test
    void aSuccessfulLogin_clearsTheEmailFailures() {
        for (int i = 0; i < 4; i++) limiter.recordFailure("chef@hydra.tn", "10.0.0.1");
        limiter.recordSuccess("chef@hydra.tn");
        limiter.recordFailure("chef@hydra.tn", "10.0.0.1");
        assertThat(limiter.isBlocked("chef@hydra.tn", "10.0.0.1")).isFalse();
    }
}
