package ai.nubase.common.util;

import org.springframework.web.util.HtmlUtils;

/**
 * HTML text-node escaping for small, hand-built HTML responses (OAuth error pages).
 * Prevents reflected XSS when query parameters or exception messages are echoed into HTML.
 */
public final class HtmlEscape {

    private HtmlEscape() {}

    /** Returns an empty string for null so callers can format without extra null checks. */
    public static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
