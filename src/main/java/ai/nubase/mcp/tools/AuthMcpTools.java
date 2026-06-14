package ai.nubase.mcp.tools;

import ai.nubase.auth.dto.request.admin.CreateUserRequest;
import ai.nubase.auth.dto.response.UserResponse;
import ai.nubase.auth.service.AdminService;
import ai.nubase.common.context.MultiTenancyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuthMcpTools {

    private final AdminService adminService;

    @Tool(description = "List auth users for the current project. Parameters: page optional default 1, perPage optional default 50 max 100, keyword optional.")
    public Object authListUsers(Integer page, Integer perPage, String keyword) {
        return adminService.listUsers(page == null ? 1 : page, perPage == null ? 50 : perPage, blankToNull(keyword));
    }

    @Tool(description = "Create an auth user. Parameters: email required, password optional, phone optional, role optional. Requires service_role MCP apikey.")
    public Object authCreateUser(String email, String password, String phone, String role) {
        Map<String, Object> guard = requireServiceRole("create auth user");
        if (guard != null) return guard;
        if (email == null || email.isBlank()) return Map.of("success", false, "error", "email is required");
        CreateUserRequest request = new CreateUserRequest();
        request.setEmail(email);
        request.setPassword(blankToNull(password));
        request.setPhone(blankToNull(phone));
        request.setRole(blankToNull(role));
        request.setEmailConfirm(true);
        UserResponse user = adminService.createUser(request);
        return Map.of("success", true, "user", user);
    }

    @Tool(description = "Delete an auth user by UUID. Parameters: userId required, softDelete optional default false. Requires service_role MCP apikey.")
    public Object authDeleteUser(String userId, Boolean softDelete) {
        Map<String, Object> guard = requireServiceRole("delete auth user");
        if (guard != null) return guard;
        if (userId == null || userId.isBlank()) return Map.of("success", false, "error", "userId is required");
        UserResponse user = adminService.deleteUserById(UUID.fromString(userId), Boolean.TRUE.equals(softDelete));
        return Map.of("success", true, "user", user);
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
