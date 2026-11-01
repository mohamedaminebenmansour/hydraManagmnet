package tn.hydra.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.document.controller.SystemStorageController;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** GET /api/system/storage (SystemStorageController): owner only (fix: storage made visible). */
@SpringBootTest
@Transactional
@Rollback
class StorageStatusDbTest {

    @Autowired private SystemStorageController controller;
    @Autowired private JdbcTemplate jdbc;

    private UsernamePasswordAuthenticationToken user(boolean owner) {
        UUID orgId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String email = "storage." + id + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Storage Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, ?, true)
                """, id, orgId, email, "hash", "Storage User", owner);
        return new UsernamePasswordAuthenticationToken(email, null, List.of());
    }

    @Test
    void refusesANonOwner() {
        assertThrows(AccessDeniedException.class, () -> controller.storage(user(false)));
    }

    @Test
    void ownerGetsTheStorageType() {
        var status = controller.storage(user(true));
        assertThat(status.type()).isEqualTo("local"); // test config: hydra.storage.type=local
    }
}
