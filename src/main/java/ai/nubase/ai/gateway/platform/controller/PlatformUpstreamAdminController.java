package ai.nubase.ai.gateway.platform.controller;

import ai.nubase.ai.gateway.platform.PlatformUpstream;
import ai.nubase.ai.gateway.platform.PlatformUpstreamRepository;
import ai.nubase.ai.gateway.platform.PlatformUpstreamService;
import ai.nubase.ai.gateway.platform.dto.PlatformUpstreamDtos.PlatformUpstreamRequest;
import ai.nubase.ai.gateway.platform.dto.PlatformUpstreamDtos.PlatformUpstreamResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ConcurrentModificationException;
import java.util.List;

/**
 * Platform control-plane CRUD for the unified upstream config (the fallback used when a project has
 * no custom upstream). Cross-tenant: authenticated by {@code AdminInitAuthFilter} (platform JWT with
 * super-admin scope, or the metadata service-role key), NOT by tenant service_role.
 */
@RestController
@RequestMapping("/ai-gateway/platform/v1/upstreams")
@RequiredArgsConstructor
public class PlatformUpstreamAdminController {

    private final PlatformUpstreamRepository repository;
    private final PlatformUpstreamService platformUpstreamService;

    @GetMapping
    public ResponseEntity<List<PlatformUpstreamResponse>> list() {
        return ResponseEntity.ok(repository.findAll().stream().map(PlatformUpstreamResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlatformUpstreamResponse> get(@PathVariable Long id) {
        PlatformUpstream u = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "platform upstream not found"));
        return ResponseEntity.ok(PlatformUpstreamResponse.from(u));
    }

    @PostMapping
    public ResponseEntity<PlatformUpstreamResponse> create(@RequestBody PlatformUpstreamRequest req) {
        validate(req, true);
        PlatformUpstream saved = save(req.toEntity(null));
        platformUpstreamService.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(PlatformUpstreamResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlatformUpstreamResponse> update(@PathVariable Long id,
                                                           @RequestBody PlatformUpstreamRequest req) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "platform upstream not found");
        }
        validate(req, false);
        PlatformUpstream saved = save(req.toEntity(id));
        platformUpstreamService.refresh();
        return ResponseEntity.ok(PlatformUpstreamResponse.from(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        platformUpstreamService.refresh();
        return ResponseEntity.noContent().build();
    }

    /** Force-reload the in-memory routing snapshot (e.g. after out-of-band DB edits). */
    @PostMapping("/cache/refresh")
    public ResponseEntity<Void> refresh() {
        platformUpstreamService.refresh();
        return ResponseEntity.noContent().build();
    }

    private PlatformUpstream save(PlatformUpstream upstream) {
        try {
            return repository.save(upstream);
        } catch (ConcurrentModificationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    private void validate(PlatformUpstreamRequest req, boolean creating) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is required");
        }
        if (creating && (req.authToken() == null || req.authToken().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authToken is required when creating an upstream");
        }
    }
}
