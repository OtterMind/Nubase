package ai.nubase.functions.executor;

import java.util.List;
import java.util.Map;

public record EdgeFunctionInvocationResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        String errorCode,
        String errorMessage
) {
    public static EdgeFunctionInvocationResponse error(int statusCode, String code, String message) {
        return new EdgeFunctionInvocationResponse(statusCode, Map.of(), new byte[0], code, message);
    }
}
