package tn.hydra.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed-login limit, in memory (single instance): at most {@link #MAX_PER_EMAIL} failures
 * per email and {@link #MAX_PER_IP} per IP in a sliding {@link #WINDOW_MS} window. Once a
 * key reaches its limit, logins are refused (429) until its oldest failure leaves the window.
 * A successful login clears the email's failures (not the IP's).
 */
@Component
public class LoginRateLimiter {

    public static final int MAX_PER_EMAIL = 5;
    public static final int MAX_PER_IP = 20;
    public static final long WINDOW_MS = 15 * 60 * 1000L;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter() { this(Clock.systemUTC()); }

    LoginRateLimiter(Clock clock) { this.clock = clock; }

    /** True when this email or this IP already reached its limit in the window. */
    public boolean isBlocked(String email, String ip) {
        long now = clock.millis();
        return count(emailKey(email), now) >= MAX_PER_EMAIL || count(ipKey(ip), now) >= MAX_PER_IP;
    }

    public void recordFailure(String email, String ip) {
        long now = clock.millis();
        add(emailKey(email), now);
        add(ipKey(ip), now);
    }

    public void recordSuccess(String email) {
        failures.remove(emailKey(email));
    }

    private int count(String key, long now) {
        Deque<Long> q = failures.get(key);
        if (q == null) return 0;
        synchronized (q) {
            prune(q, now);
            if (q.isEmpty()) failures.remove(key, q);
            return q.size();
        }
    }

    private void add(String key, long now) {
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
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
}
