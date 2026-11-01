package tn.hydra.document.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.hydra.document.service.StorageStatusService;

/** GET /api/system/storage — owner only: { type: "local" | "s3", persistent }. */
@RestController
public class SystemStorageController {

    private final StorageStatusService storageStatus;

    public SystemStorageController(StorageStatusService storageStatus) {
        this.storageStatus = storageStatus;
    }

    @GetMapping("/api/system/storage")
    public StorageStatusService.StorageStatus storage(Authentication auth) {
        return storageStatus.status(auth);
    }
}
