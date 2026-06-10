package ai.nubase.functions.controller;

import ai.nubase.auth.annotation.RequireServiceRole;
import ai.nubase.functions.dto.EdgeFunctionDtos.CreateFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.DeployFunctionRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.EdgeFunctionResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.EdgeFunctionVersionResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.FunctionSecretResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.InvocationLogResponse;
import ai.nubase.functions.dto.EdgeFunctionDtos.SetFunctionSecretsRequest;
import ai.nubase.functions.dto.EdgeFunctionDtos.UpdateFunctionRequest;
import ai.nubase.functions.service.EdgeFunctionAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/functions/admin/v1")
@RequireServiceRole
@RequiredArgsConstructor
public class EdgeFunctionAdminController {

    private final EdgeFunctionAdminService adminService;

    @GetMapping("/functions")
    public ResponseEntity<List<EdgeFunctionResponse>> list() {
        return ResponseEntity.ok(adminService.listFunctions().stream().map(EdgeFunctionResponse::from).toList());
    }

    @PostMapping("/functions")
    public ResponseEntity<EdgeFunctionResponse> create(
            @Valid @RequestBody CreateFunctionRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(EdgeFunctionResponse.from(adminService.createFunction(request, platformUserId(servletRequest))));
    }

    @GetMapping("/functions/{slug}")
    public ResponseEntity<EdgeFunctionResponse> get(@PathVariable String slug) {
        return ResponseEntity.ok(EdgeFunctionResponse.from(adminService.getFunction(slug)));
    }

    @PatchMapping("/functions/{slug}")
    public ResponseEntity<EdgeFunctionResponse> update(
            @PathVariable String slug,
            @Valid @RequestBody UpdateFunctionRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(EdgeFunctionResponse.from(adminService.updateFunction(slug, request, platformUserId(servletRequest))));
    }

    @PostMapping("/functions/{slug}/deploy")
    public ResponseEntity<EdgeFunctionVersionResponse> deploy(
            @PathVariable String slug,
            @Valid @RequestBody DeployFunctionRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(EdgeFunctionVersionResponse.from(adminService.deploy(slug, request, platformUserId(servletRequest))));
    }

    @GetMapping("/functions/{slug}/versions")
    public ResponseEntity<List<EdgeFunctionVersionResponse>> versions(@PathVariable String slug) {
        return ResponseEntity.ok(adminService.listVersions(slug).stream().map(EdgeFunctionVersionResponse::from).toList());
    }

    @DeleteMapping("/functions/{slug}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String slug) {
        adminService.deleteFunction(slug);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/functions/{slug}/secrets")
    public ResponseEntity<List<FunctionSecretResponse>> listSecrets(@PathVariable String slug) {
        return ResponseEntity.ok(adminService.listSecrets(slug).stream().map(FunctionSecretResponse::from).toList());
    }

    @PostMapping("/functions/{slug}/secrets")
    public ResponseEntity<List<FunctionSecretResponse>> setSecrets(
            @PathVariable String slug,
            @Valid @RequestBody SetFunctionSecretsRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(adminService.setSecrets(slug, request, platformUserId(servletRequest))
                .stream().map(FunctionSecretResponse::from).toList());
    }

    @GetMapping("/invocations")
    public ResponseEntity<List<InvocationLogResponse>> invocations(
            @RequestParam(required = false) String function,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(adminService.listInvocations(function, limit).stream()
                .map(InvocationLogResponse::from)
                .toList());
    }

    private UUID platformUserId(HttpServletRequest request) {
        Object value = request.getAttribute("platformUserId");
        return value instanceof UUID id ? id : null;
    }
}
