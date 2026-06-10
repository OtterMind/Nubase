package ai.nubase.functions.service;

import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeFunctionRateLimiterTest {

    @Test
    void rejectsRequestsAboveProjectLimit() {
        EdgeFunctionExecutorProperties properties = new EdgeFunctionExecutorProperties();
        properties.setPerProjectRpm(1);
        properties.setPerFunctionRpm(0);
        EdgeFunctionRateLimiter limiter = new EdgeFunctionRateLimiter(properties);

        limiter.check("project-a", "hello");

        assertThatThrownBy(() -> limiter.check("project-a", "world"))
                .isInstanceOf(EdgeFunctionException.class)
                .extracting(ex -> ((EdgeFunctionException) ex).status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void disabledLimitsDoNotReject() {
        EdgeFunctionExecutorProperties properties = new EdgeFunctionExecutorProperties();
        properties.setPerProjectRpm(0);
        properties.setPerFunctionRpm(0);
        EdgeFunctionRateLimiter limiter = new EdgeFunctionRateLimiter(properties);

        for (int i = 0; i < 10; i += 1) {
            limiter.check("project-a", "hello");
        }
    }

    @Test
    void rejectsRequestsAboveRedisLimit() {
        EdgeFunctionExecutorProperties properties = new EdgeFunctionExecutorProperties();
        properties.setPerProjectRpm(1);
        properties.setPerFunctionRpm(0);
        EdgeFunctionRateLimiter limiter = new EdgeFunctionRateLimiter(properties);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
        ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);

        limiter.check("project-a", "hello");

        assertThatThrownBy(() -> limiter.check("project-a", "world"))
                .isInstanceOf(EdgeFunctionException.class)
                .extracting(ex -> ((EdgeFunctionException) ex).status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
