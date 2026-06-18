package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter + failed-login lockout for auth endpoints. Keys are namespaced by
 * tenant + action + identity.
 *
 * <p>When a {@link StringRedisTemplate} bean is present (Redis configured), state is shared
 * across the fleet via Redis counters with TTLs — correct for horizontally-scaled deployments.
 * Otherwise it falls back to a per-JVM in-process implementation (fine for single-/few-node).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    /**
     * INCR 与首次 EXPIRE 必须在同一 Lua 脚本里完成：分两次调用时，若在 EXPIRE 前进程崩溃，
     * 会留下无 TTL 的计数 key，窗口永不重置（永久 429）或失败计数永不衰减。
     * 与 {@link ai.nubase.functions.service.EdgeFunctionRateLimiter} 使用相同模式。
     */
    private static final RedisScript<Long> INCREMENT_WITH_EXPIRE_SCRIPT = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final EffectiveAuthConfig effectiveAuthConfig;

    /** Optional — present only when Redis is configured. Null → in-process fallback. */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    private record FailureState(int count, long lockedUntilMillis) {}

    private boolean useRedis() {
        return redisTemplate != null;
    }

    /**
     * Enforce a request cap for a sensitive action within the configured window.
     *
     * @throws RateLimitExceededException when the cap is exceeded
     */
    public void checkRate(String action, String identifier) {
        AuthConfig.RateLimitSettings cfg = effectiveAuthConfig.rateLimit();
        if (!cfg.isEnabled()) {
            return;
        }
        if (useRedis()) {
            checkRateRedis(cfg, action, identifier);
            return;
        }
        String key = key(action, identifier);
        long now = System.currentTimeMillis();
        long windowMs = cfg.getWindowSeconds() * 1000L;

        Deque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) {
                hits.pollFirst();
            }
            if (hits.size() >= cfg.getMaxRequests()) {
                throw new RateLimitExceededException(
                        "Rate limit exceeded for " + action + ". Please try again later.");
            }
            hits.addLast(now);
        }
    }

    /**
     * Throw if the identity is currently locked out due to too many failed sign-ins.
     */
    public void assertNotLockedOut(String identifier) {
        AuthConfig.RateLimitSettings cfg = effectiveAuthConfig.rateLimit();
        if (!cfg.isEnabled()) {
            return;
        }
        if (useRedis()) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey("rl:lock:" + tenant() + ":" + identifier))) {
                throw new RateLimitExceededException("Too many failed attempts. Account temporarily locked.");
            }
            return;
        }
        FailureState state = failures.get(lockKey(identifier));
        if (state != null && state.lockedUntilMillis() > System.currentTimeMillis()) {
            long secs = (state.lockedUntilMillis() - System.currentTimeMillis()) / 1000 + 1;
            throw new RateLimitExceededException(
                    "Too many failed attempts. Account temporarily locked for " + secs + "s.");
        }
    }

    /** Record a failed sign-in; lock the identity once the threshold is crossed. */
    public void recordFailure(String identifier) {
        AuthConfig.RateLimitSettings cfg = effectiveAuthConfig.rateLimit();
        if (!cfg.isEnabled()) {
            return;
        }
        if (useRedis()) {
            recordFailureRedis(cfg, identifier);
            return;
        }
        String key = lockKey(identifier);
        failures.compute(key, (k, prev) -> {
            int count = (prev == null ? 0 : prev.count()) + 1;
            long lockedUntil = 0;
            if (count >= cfg.getMaxFailedLogins()) {
                lockedUntil = System.currentTimeMillis() + cfg.getLockoutSeconds() * 1000L;
                log.warn("Identity '{}' locked out after {} failed attempts", identifier, count);
            }
            return new FailureState(count, lockedUntil);
        });
    }

    /** Clear failed-attempt state after a successful sign-in. */
    public void recordSuccess(String identifier) {
        if (useRedis()) {
            redisTemplate.delete("rl:fail:" + tenant() + ":" + identifier);
            redisTemplate.delete("rl:lock:" + tenant() + ":" + identifier);
            return;
        }
        failures.remove(lockKey(identifier));
    }

    // ---------------------------------------------------------------- Redis backend

    private void checkRateRedis(AuthConfig.RateLimitSettings cfg, String action, String identifier) {
        String redisKey = "rl:" + key(action, identifier);
        long ttlMillis = cfg.getWindowSeconds() * 1000L;
        Long n = redisIncrementWithExpire(redisKey, ttlMillis);
        if (n != null && n > cfg.getMaxRequests()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for " + action + ". Please try again later.");
        }
    }

    private void recordFailureRedis(AuthConfig.RateLimitSettings cfg, String identifier) {
        String countKey = "rl:fail:" + tenant() + ":" + identifier;
        long ttlMillis = cfg.getLockoutSeconds() * 1000L;
        Long n = redisIncrementWithExpire(countKey, ttlMillis);
        if (n != null && n >= cfg.getMaxFailedLogins()) {
            redisTemplate.opsForValue().set("rl:lock:" + tenant() + ":" + identifier, "1",
                    Duration.ofSeconds(cfg.getLockoutSeconds()));
            log.warn("Identity '{}' locked out after {} failed attempts (redis)", identifier, n);
        }
    }

    private Long redisIncrementWithExpire(String redisKey, long ttlMillis) {
        return redisTemplate.execute(
                INCREMENT_WITH_EXPIRE_SCRIPT,
                List.of(redisKey),
                String.valueOf(ttlMillis));
    }

    private String key(String action, String identifier) {
        return tenant() + ":" + action + ":" + identifier;
    }

    private String lockKey(String identifier) {
        return tenant() + ":lock:" + identifier;
    }

    private String tenant() {
        String appCode = ai.nubase.common.context.MultiTenancyContext.getAppCode();
        return appCode != null ? appCode : "_";
    }

    /** Thrown when an auth rate limit or lockout is hit (mapped to HTTP 429). */
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
