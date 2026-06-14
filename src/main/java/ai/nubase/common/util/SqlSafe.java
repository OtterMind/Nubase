package ai.nubase.common.util;

/**
 * Minimal PostgreSQL identifier / string-literal quoting for the few places that
 * must build DDL or {@code SET} statements by string concatenation (identifiers
 * and DDL targets cannot be JDBC bind parameters).
 *
 * <p>Defence-in-depth: callers already validate identifiers against an allowlist
 * (e.g. {@code ^[a-z][a-z0-9_]*$}), so for legitimate input the quoted form is
 * behaviourally identical — this only closes the residual injection surface if a
 * validation is ever bypassed or relaxed.
 */
public final class SqlSafe {
    private SqlSafe() {}

    /** Double-quote a SQL identifier, escaping any embedded double quotes. */
    public static String ident(String name) {
        if (name == null) throw new IllegalArgumentException("identifier must not be null");
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Single-quote a SQL string literal, escaping any embedded single quotes. */
    public static String literal(String value) {
        if (value == null) throw new IllegalArgumentException("literal must not be null");
        return "'" + value.replace("'", "''") + "'";
    }
}
