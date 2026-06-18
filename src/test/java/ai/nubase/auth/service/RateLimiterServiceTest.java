package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RateLimiterService} (sliding-window cap + failed-login lockout).
 */
@DisplayName("RateLimiterService")
class RateLimiterServiceTest {

    private RateLimiterService limiter(int maxReq, int window, int maxFail, int lockout) {
        AuthConfig cfg = new AuthConfig();
        cfg.getRateLimit().setEnabled(true);
        cfg.getRateLimit().setMaxRequests(maxReq);
        cfg.getRateLimit().setWindowSeconds(window);
        cfg.getRateLimit().setMaxFailedLogins(maxFail);
        cfg.getRateLimit().setLockoutSeconds(lockout);
        return new RateLimiterService(new EffectiveAuthConfig(cfg));
    }

    private RateLimiterService redisLimiter(int maxReq, int window, int maxFail, int lockout) {
        RateLimiterService rl = limiter(maxReq, window, maxFail, lockout);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(rl, "redisTemplate", redisTemplate);
        return rl;
    }

    @Test
    @DisplayName("checkRate allows up to the cap, then throws")
    void rateCap() {
        RateLimiterService rl = limiter(2, 300, 5, 900);
        assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
        assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> rl.checkRate("otp", "a@x.com"))
                .isInstanceOf(RateLimiterService.RateLimitExceededException.class);
    }

    @Test
    @DisplayName("checkRate buckets are independent per action and identifier")
    void independentBuckets() {
        RateLimiterService rl = limiter(1, 300, 5, 900);
        assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
        // different identifier — own bucket
        assertThatCode(() -> rl.checkRate("otp", "b@x.com")).doesNotThrowAnyException();
        // different action — own bucket
        assertThatCode(() -> rl.checkRate("recover", "a@x.com")).doesNotThrowAnyException();
        // same action+identifier again — over cap
        assertThatThrownBy(() -> rl.checkRate("otp", "a@x.com"))
                .isInstanceOf(RateLimiterService.RateLimitExceededException.class);
    }

    @Test
    @DisplayName("lockout triggers after maxFailedLogins and clears on success")
    void lockout() {
        RateLimiterService rl = limiter(100, 300, 3, 900);
        String id = "victim@x.com";
        assertThatCode(() -> rl.assertNotLockedOut(id)).doesNotThrowAnyException();

        rl.recordFailure(id);
        rl.recordFailure(id);
        assertThatCode(() -> rl.assertNotLockedOut(id)).doesNotThrowAnyException(); // 2 < 3
        rl.recordFailure(id);
        assertThatThrownBy(() -> rl.assertNotLockedOut(id))
                .isInstanceOf(RateLimiterService.RateLimitExceededException.class); // 3 >= 3 → locked

        rl.recordSuccess(id);
        assertThatCode(() -> rl.assertNotLockedOut(id)).doesNotThrowAnyException(); // cleared
    }

    @Test
    @DisplayName("disabled limiter never throttles or locks out")
    void disabled() {
        AuthConfig cfg = new AuthConfig();
        cfg.getRateLimit().setEnabled(false);
        cfg.getRateLimit().setMaxRequests(1);
        cfg.getRateLimit().setMaxFailedLogins(1);
        RateLimiterService rl = new RateLimiterService(new EffectiveAuthConfig(cfg));

        for (int i = 0; i < 10; i++) {
            int finalI = i;
            assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
            rl.recordFailure("a@x.com");
        }
        assertThatCode(() -> rl.assertNotLockedOut("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis checkRate uses atomic INCR+PEXPIRE script and enforces cap")
    void redisRateCapUsesAtomicScript() {
        RateLimiterService rl = redisLimiter(2, 300, 5, 900);
        StringRedisTemplate redisTemplate =
                (StringRedisTemplate) ReflectionTestUtils.getField(rl, "redisTemplate");
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
                .thenReturn(1L, 2L, 3L);

        assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
        assertThatCode(() -> rl.checkRate("otp", "a@x.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> rl.checkRate("otp", "a@x.com"))
                .isInstanceOf(RateLimiterService.RateLimitExceededException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(3))
                .execute(ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), eq("300000"));
        assertThat(keysCaptor.getValue()).containsExactly("rl:_:otp:a@x.com");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("Redis recordFailure uses atomic INCR+PEXPIRE script before lockout")
    void redisFailureCountUsesAtomicScript() {
        RateLimiterService rl = redisLimiter(100, 300, 3, 900);
        StringRedisTemplate redisTemplate =
                (StringRedisTemplate) ReflectionTestUtils.getField(rl, "redisTemplate");
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
                .thenReturn(1L, 2L, 3L);
        when(redisTemplate.hasKey("rl:lock:_:victim@x.com")).thenReturn(false, true);

        String id = "victim@x.com";
        rl.recordFailure(id);
        rl.recordFailure(id);
        assertThatCode(() -> rl.assertNotLockedOut(id)).doesNotThrowAnyException();
        rl.recordFailure(id);
        assertThatThrownBy(() -> rl.assertNotLockedOut(id))
                .isInstanceOf(RateLimiterService.RateLimitExceededException.class);

        verify(redisTemplate, times(3))
                .execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), eq("900000"));
        verify(valueOps).set(eq("rl:lock:_:victim@x.com"), eq("1"), eq(Duration.ofSeconds(900)));
    }
}
