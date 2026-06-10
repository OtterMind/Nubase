package ai.nubase.functions.util;

import java.util.Locale;

public final class EdgeFunctionNames {

    private EdgeFunctionNames() {
    }

    public static String normalizeSlug(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Function slug is required");
        }
        String slug = value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (!isValidSlug(slug)) {
            throw new IllegalArgumentException("Function slug must match ^[a-zA-Z0-9_-]{1,128}$");
        }
        return slug;
    }

    public static boolean isValidSlug(String value) {
        return value != null && value.matches("^[a-zA-Z0-9_-]{1,128}$");
    }

    public static boolean isValidSecretName(String value) {
        return value != null && value.matches("^[A-Z_][A-Z0-9_]{0,127}$");
    }
}
