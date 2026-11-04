package tn.hydra.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for POST /api/auth/forgot-password (U2): at most
 * {@link #MAX_PER_EMAIL} requests per email and {@link #MAX_PER_IP} per IP
 * in a sliding {@link #WINDOW_MS} window (1 hour). In-memory, single instance.
 *
 * <p>Unlike {@link LoginRateLimiter}, this counts ALL requests (not just
 * failures) because the endpoint always returns 200 — the rate limit is the
 * only way to prevent abuse.
 */
@Component
public class ForgotPasswordRateLimiter {

    public static final int MAX_PER_EMAIL = 3;
    public static final int MAX_PER_IP = 10;
    public static final long WINDOW_MS = 60 * 60 * 1000L; // 1 hour

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public ForgotPasswordRateLimiter() { this(Clock.systemUTC()); }
    ForgotPasswordRateLimiter(Clock clock) { this.clock = clock; }

    /** True when this email or this IP already reached its limit in the window. */
    public boolean isBlocked(String email, String ip) {
        long now = clock.millis();
        return count(emailKey(email), now) >= MAX_PER_EMAIL
                || count(ipKey(ip), now) >= MAX_PER_IP;
    }

    /** Record a request. Call this AFTER the check, even when blocked (so the count advances). */
    public void record(String email, String ip) {
        long now = clock.millis();
        add(emailKey(email), now);
        add(ipKey(ip), now);
    }

    private int count(String key, long now) {
        Deque<Long> q = hits.get(key);
        if (q == null) return 0;
        synchronized (q) {
            prune(q, now);
            if (q.isEmpty()) hits.remove(key, q);
            return q.size();
        }
    }

    private void add(String key, long now) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
        }
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
    }

    private static String emailKey(String email) {
        return "e:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "i:" + (ip == null ? "" : ip);
    }

    // ── U7: reset-password wrong-token rate limit ──────────────────────

    private static final int RESET_MAX_PER_IP = 5;
    private static final long RESET_WINDOW_MS = 15 * 60 * 1000L;
    private final Map<String, Deque<Long>> resetFailures = new ConcurrentHashMap<>();

    /** True when this IP has reached 5 TOKEN_INVALID failures in 15 minutes. */
    public boolean isResetBlocked(String ip) {
        long now = clock.millis();
        return resetCount(resetKey(ip), now) >= RESET_MAX_PER_IP;
    }

    /** Record a TOKEN_INVALID failure for the reset endpoint. */
    public void recordResetFailure(String ip) {
        long now = clock.millis();
        resetAdd(resetKey(ip), now);
    }

    private int resetCount(String key, long now) {
        Deque<Long> q = resetFailures.get(key);
        if (q == null) return 0;
        synchronized (q) {
            resetPrune(q, now);
            if (q.isEmpty()) resetFailures.remove(key, q);
            return q.size();
        }
    }

    private void resetAdd(String key, long now) {
        Deque<Long> q = resetFailures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            resetPrune(q, now);
            q.addLast(now);
        }
    }

    private static void resetPrune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= RESET_WINDOW_MS) q.pollFirst();
    }

    private static String resetKey(String ip) {
        return "r:" + (ip == null ? "" : ip);
    }
}
