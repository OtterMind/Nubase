package ai.nubase.common.util;

/**
 * 网关与审计日志中的 API Key 脱敏，保留首尾各 4 位便于排障关联。
 */
public final class ApiKeyLogMask {

    private ApiKeyLogMask() {}

    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
