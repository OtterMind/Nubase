package ai.nubase.functions.service;

import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class EdgeFunctionRateLimiter {

    private final EdgeFunctionExecutorProperties properties;
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public void check(String projectRef, String functionSlug) {
        check("project:" + projectRef, properties.getPerProjectRpm());
        check("function:" + projectRef + ":" + functionSlug, properties.getPerFunctionRpm());
    }

    private void check(String key, int limit) {
        if (limit <= 0) return;
        if (redisTemplate != null) {
            try {
                checkRedis(key, limit);
                return;
            } catch (EdgeFunctionException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Edge function Redis rate limiter unavailable; falling back to local counter: {}", ex.toString());
            }
        }
        int count = counters.get(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (count > limit) {
            throwRateLimited();
        }
    }

    private void checkRedis(String key, int limit) {
        String redisKey = "edge:functions:rl:" + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, Duration.ofMinutes(1));
        }
        if (count != null && count > limit) {
            throwRateLimited();
        }
    }

    private void throwRateLimited() {
        throw new EdgeFunctionException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Function invocation rate limit exceeded"
        );
    }
}
