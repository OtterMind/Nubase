package ai.nubase.auth.service;

import ai.nubase.auth.dto.oauth.OAuthStateData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OAuthStateService")
class OAuthStateServiceTest {

    @Test
    @DisplayName("stores state in Redis")
    void saveStateStoresStateInRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        OAuthStateService service = new OAuthStateService(redisTemplate);
        OAuthStateData stateData = OAuthStateData.builder()
                .provider("google")
                .apikey("project-key")
                .createdAt(123L)
                .build();

        service.saveState("state-1", stateData);

        verify(valueOps).set(
                eq("oauth:state:state-1"),
                eq("{\"provider\":\"google\",\"apikey\":\"project-key\",\"redirectTo\":null,\"callbackUrl\":null,\"createdAt\":123,\"codeChallenge\":null,\"codeChallengeMethod\":null,\"linkUserId\":null}"),
                eq(Duration.ofMinutes(10))
        );
    }

    @Test
    @DisplayName("retrieves and removes state from Redis")
    void getAndRemoveStateUsesRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("oauth:state:state-1")).thenReturn(
                "{\"provider\":\"google\",\"apikey\":\"project-key\",\"redirectTo\":null,\"callbackUrl\":\"https://app.example.com/auth/v1/callback\",\"createdAt\":123,\"codeChallenge\":null,\"codeChallengeMethod\":null,\"linkUserId\":null}"
        );
        OAuthStateService service = new OAuthStateService(redisTemplate);

        OAuthStateData stateData = service.getAndRemoveState("state-1");

        assertThat(stateData).isNotNull();
        assertThat(stateData.getProvider()).isEqualTo("google");
        assertThat(stateData.getCallbackUrl()).isEqualTo("https://app.example.com/auth/v1/callback");
        verify(redisTemplate).delete("oauth:state:state-1");
    }
}
