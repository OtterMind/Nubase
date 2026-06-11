package ai.nubase.cron.target;

import ai.nubase.cron.target.ScheduledJobTarget.RunOutcome;
import ai.nubase.functions.executor.EdgeFunctionInvocationResponse;
import ai.nubase.functions.service.EdgeFunctionExceptions.EdgeFunctionException;
import ai.nubase.functions.service.EdgeFunctionInvocationCommand;
import ai.nubase.functions.service.EdgeFunctionInvocationService;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EdgeFunctionJobTargetTest {

    @Mock
    private ObjectProvider<EdgeFunctionInvocationService> provider;
    @Mock
    private EdgeFunctionInvocationService invocationService;

    @Test
    void invokesWithCronRoleAndDefaults() {
        when(provider.getIfAvailable()).thenReturn(invocationService);
        when(invocationService.invoke(eq("hello"), any()))
                .thenReturn(new EdgeFunctionInvocationResponse(200, Map.of(), new byte[0], null, null));
        EdgeFunctionJobTarget target = new EdgeFunctionJobTarget(provider);

        RunOutcome outcome = target.execute(job("hello", null, "webhooks/tick", "{\"a\":1}"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result()).isEqualTo("HTTP 200");
        org.mockito.ArgumentCaptor<EdgeFunctionInvocationCommand> captor =
                org.mockito.ArgumentCaptor.forClass(EdgeFunctionInvocationCommand.class);
        verify(invocationService).invoke(eq("hello"), captor.capture());
        EdgeFunctionInvocationCommand command = captor.getValue();
        assertThat(command.callerRole()).isEqualTo(EdgeFunctionInvocationCommand.ROLE_CRON);
        assertThat(command.method()).isEqualTo("POST");
        assertThat(command.path()).isEqualTo("/webhooks/tick");
        assertThat(command.headers()).containsKey("content-type");
    }

    @Test
    void httpErrorStatusIsAFailedRun() {
        when(provider.getIfAvailable()).thenReturn(invocationService);
        when(invocationService.invoke(eq("hello"), any()))
                .thenReturn(new EdgeFunctionInvocationResponse(500, Map.of(), "oops".getBytes(), null, null));
        EdgeFunctionJobTarget target = new EdgeFunctionJobTarget(provider);

        RunOutcome outcome = target.execute(job("hello", null, null, null));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.result()).isEqualTo("HTTP 500");
        assertThat(outcome.errorMessage()).contains("oops");
    }

    @Test
    void edgeFunctionExceptionIsAFailedRun() {
        when(provider.getIfAvailable()).thenReturn(invocationService);
        when(invocationService.invoke(eq("missing"), any()))
                .thenThrow(new EdgeFunctionException(HttpStatus.NOT_FOUND, "FUNCTION_NOT_FOUND", "Function not found"));
        EdgeFunctionJobTarget target = new EdgeFunctionJobTarget(provider);

        RunOutcome outcome = target.execute(job("missing", null, null, null));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorMessage()).contains("FUNCTION_NOT_FOUND");
    }

    @Test
    void disabledFunctionsModuleFailsCleanly() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EdgeFunctionInvocationService> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        EdgeFunctionJobTarget target = new EdgeFunctionJobTarget(empty);

        RunOutcome outcome = target.execute(job("hello", null, null, null));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorMessage()).contains("FUNCTIONS_DISABLED");
    }

    private ScheduledJob job(String slug, String method, String path, String body) {
        return ScheduledJob.builder()
                .projectRef("app1")
                .name("job")
                .targetType(ScheduledJob.TARGET_EDGE_FUNCTION)
                .functionSlug(slug)
                .httpMethod(method)
                .requestPath(path)
                .requestBody(body)
                .build();
    }
}
