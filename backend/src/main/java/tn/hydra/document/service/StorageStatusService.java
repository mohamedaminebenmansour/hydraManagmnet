package tn.hydra.document.service;

import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.Arrays;

/**
 * Which file storage is active, and whether files survive a redeploy.
 * "local" writes to the container disk: lost on the next deploy, except on a
 * developer machine or in tests (profiles dev / test). "s3" is persistent.
 */
@Service
public class StorageStatusService {

    public record StorageStatus(String type, boolean persistent) {}

    private final Environment env;
    private final AppUserRepository users;

    public StorageStatusService(Environment env, AppUserRepository users) {
        this.env = env;
        this.users = users;
    }

    /** Owner only (the banner is for the owner). */
    public StorageStatus status(Authentication auth) {
        AppUser me = auth == null ? null : users.findByEmail(auth.getName()).orElse(null);
        if (me == null || !Boolean.TRUE.equals(me.getIsOwner()))
            throw new AccessDeniedException("Réservé au propriétaire du compte");
        // Same property as LocalFileStorage / S3FileStorage (missing = local).
        String type = "s3".equalsIgnoreCase(env.getProperty("hydra.storage.type", "local").trim()) ? "s3" : "local";
        boolean devOrTest = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("test"));
        return new StorageStatus(type, "s3".equals(type) || devOrTest);
    }
}
