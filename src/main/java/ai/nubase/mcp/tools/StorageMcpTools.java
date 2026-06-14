package ai.nubase.mcp.tools;

import ai.nubase.auth.dto.storage.BucketDTO;
import ai.nubase.auth.dto.storage.CreateBucketRequest;
import ai.nubase.auth.service.BucketService;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class StorageMcpTools {

    private final BucketService bucketService;

    @Tool(description = "List Nubase storage buckets. Parameters: search optional, limit optional, offset optional.")
    public Object storageListBuckets(String search, Integer limit, Integer offset) {
        return bucketService.listBuckets(limit, offset, "id", "asc", blankToNull(search));
    }

    @Tool(description = "Create a storage bucket. Parameters: name required, public optional default false, fileSizeLimit optional. Requires service_role MCP apikey.")
    public Object storageCreateBucket(String name, Boolean isPublic, Long fileSizeLimit) {
        Map<String, Object> guard = requireServiceRole("create storage bucket");
        if (guard != null) return guard;
        if (name == null || name.isBlank()) return Map.of("success", false, "error", "name is required");
        CreateBucketRequest request = new CreateBucketRequest();
        request.setName(name);
        request.setIsPublic(Boolean.TRUE.equals(isPublic));
        request.setFileSizeLimit(fileSizeLimit);
        BucketDTO bucket = bucketService.createBucket(request);
        return Map.of("success", true, "bucket", bucket);
    }

    @Tool(description = "Delete a storage bucket and its objects. Parameters: bucketId required. Requires service_role MCP apikey.")
    public Object storageDeleteBucket(String bucketId) {
        Map<String, Object> guard = requireServiceRole("delete storage bucket");
        if (guard != null) return guard;
        if (bucketId == null || bucketId.isBlank()) return Map.of("success", false, "error", "bucketId is required");
        bucketService.deleteBucket(bucketId);
        return Map.of("success", true, "bucketId", bucketId);
    }

    private Map<String, Object> requireServiceRole(String action) {
        if (!MultiTenancyContext.isServiceRole()) {
            return Map.of("success", false, "error", "Cannot " + action + ": service_role MCP apikey is required");
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
