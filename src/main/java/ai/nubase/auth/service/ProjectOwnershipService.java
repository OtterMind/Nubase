package ai.nubase.auth.service;

import ai.nubase.metadata.entity.PlatformUserProject;
import ai.nubase.metadata.repository.PlatformUserProjectRepository;
import ai.nubase.metadata.repository.PlatformUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records project ownership in {@code platform_user_projects} so every project has at least one
 * owner row and the listing can go through that table uniformly.
 * <p>
 * Lives in its own bean (rather than inline in the controller) so {@code @Transactional} actually
 * takes effect: the owner resolution may create a shadow platform user + external-identity mapping
 * via {@link PlatformExternalIdentityService}, and those writes plus the ownership row must commit
 * (or roll back) as one unit. A self-invoked {@code @Transactional} controller method would be
 * bypassed by the Spring proxy and silently run non-transactionally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectOwnershipService {

    private final PlatformUserProjectRepository platformUserProjectRepository;
    private final PlatformUserRepository platformUserRepository;
    private final PlatformExternalIdentityService platformExternalIdentityService;

    /**
     * Bind {@code dbKey} to an owner. May throw {@link org.springframework.dao.DataIntegrityViolationException}
     * if it loses a race creating the external mapping / ownership row — callers should retry once
     * (the second attempt finds the winning rows and becomes a no-op).
     *
     * @param callerId         the authenticated caller (a real platform user, or the system user)
     * @param dbKey            the project key
     * @param externalPlatform optional third-party platform name (with externalUserId, keys a shadow user)
     * @param externalUserId   optional third-party user id
     */
    @Transactional("metadataTransactionManager")
    public void recordOwnership(UUID callerId, String dbKey, String externalPlatform, String externalUserId) {
        if (dbKey == null) {
            return;
        }
        UUID owner = resolveOwner(callerId, externalPlatform, externalUserId);
        if (platformUserProjectRepository.existsByUserIdAndDbKey(owner, dbKey)) {
            return;
        }
        platformUserProjectRepository.save(PlatformUserProject.builder()
                .userId(owner)
                .dbKey(dbKey)
                .role("owner")
                .build());
        log.info("Recorded project ownership: user_id={}, db_key={}", owner, dbKey);
    }

    /**
     * Resolve the Nubase user that should own a project:
     * <ol>
     *   <li>a real platform caller owns their own project directly;</li>
     *   <li>otherwise (system/root caller) with third-party attribution → the dedicated, non-super-admin
     *       Nubase user mapped from {@code (externalPlatform, externalUserId)}, created on first use;</li>
     *   <li>otherwise → {@link PlatformAuthService#SYSTEM_USER_ID}.</li>
     * </ol>
     * Falls back to the system user if the resolved id is missing from {@code platform_users}.
     */
    private UUID resolveOwner(UUID callerId, String externalPlatform, String externalUserId) {
        UUID owner;
        if (callerId != null && !PlatformAuthService.SYSTEM_USER_ID.equals(callerId)) {
            owner = callerId;
        } else if (hasText(externalPlatform) && hasText(externalUserId)) {
            owner = platformExternalIdentityService.resolveOrCreatePlatformUser(externalPlatform, externalUserId);
        } else {
            owner = PlatformAuthService.SYSTEM_USER_ID;
        }
        if (owner == null || !platformUserRepository.existsById(owner)) {
            log.warn("Project owner {} not found in platform_users; using system user", owner);
            owner = PlatformAuthService.SYSTEM_USER_ID;
        }
        return owner;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
