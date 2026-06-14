package ai.nubase.assets.controller;

import ai.nubase.assets.entity.AssetFile;
import ai.nubase.assets.service.AssetsService;
import ai.nubase.common.constant.HttpHeaderConstant;
import ai.nubase.common.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Assets data plane: public, apikey-free delivery of static assets.
 * Tenant is resolved from the request subdomain ({appCode}.{domain}) by
 * UnifiedMultiTenancyFilter, the same mechanism as public Storage downloads.
 * Base path: /assets/v1
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/assets/v1")
@ConditionalOnProperty(value = "nubase.assets.enabled", havingValue = "true", matchIfMissing = true)
public class AssetsPublicController {

    private final AssetsService assetsService;

    /**
     * Serve an asset.
     * GET /assets/v1/{path}
     */
    @GetMapping("/**")
    public ResponseEntity<Resource> serve(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletRequest request
    ) {
        AssetFile file = assetsService.getPublicFileOrThrow(RequestUtil.extractPathVariable(request));
        HttpHeaders headers = buildHeaders(file);

        if (etagMatches(ifNoneMatch, file.getEtag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }

        Resource resource = new InputStreamResource(assetsService.openStream(file));
        headers.setContentLength(file.getSizeBytes());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /**
     * Asset metadata (headers only).
     * HEAD /assets/v1/{path}
     */
    @RequestMapping(value = "/**", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(HttpServletRequest request) {
        AssetFile file = assetsService.getPublicFileOrThrow(RequestUtil.extractPathVariable(request));
        HttpHeaders headers = buildHeaders(file);
        headers.setContentLength(file.getSizeBytes());
        return ResponseEntity.ok().headers(headers).build();
    }

    private HttpHeaders buildHeaders(AssetFile file) {
        HttpHeaders headers = new HttpHeaders();
        String contentType = StringUtils.isNotBlank(file.getContentType())
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setCacheControl(assetsService.resolveCacheControl(file));
        if (StringUtils.isNotBlank(file.getEtag())) {
            headers.setETag(quoteEtag(file.getEtag()));
        }
        if (file.getUpdatedAt() != null) {
            headers.set(HttpHeaderConstant.LAST_MODIFIED, DateTimeFormatter.RFC_1123_DATE_TIME
                    .withZone(ZoneOffset.UTC)
                    .format(file.getUpdatedAt()));
        }
        return headers;
    }

    /** Compare If-None-Match against the stored ETag ignoring quoting and weak validators. */
    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (StringUtils.isBlank(ifNoneMatch) || StringUtils.isBlank(etag)) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        String expected = stripEtag(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            if (stripEtag(candidate).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String stripEtag(String value) {
        String v = value.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2).trim();
        }
        return StringUtils.strip(v, "\"");
    }

    private static String quoteEtag(String etag) {
        String v = etag.trim();
        return v.startsWith("\"") ? v : "\"" + v + "\"";
    }
}
