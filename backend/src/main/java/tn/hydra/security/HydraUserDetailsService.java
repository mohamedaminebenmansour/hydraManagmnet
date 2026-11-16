package tn.hydra.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import tn.hydra.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class HydraUserDetailsService implements UserDetailsService {

    /** Columns of hydra_login_lookup (V58) that login needs. */
    private record LoginRow(UUID id, UUID orgId, String email, String passwordHash,
                            Boolean isOwner, Boolean isActive) {}

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public HydraUserDetailsService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Login runs before any org is known: hydra_login_lookup is SECURITY DEFINER,
        // so it keeps working once RLS is on (core 1b).
        List<LoginRow> rows = jdbc.query(
                "select id, org_id, email, password_hash, is_owner, is_active from hydra_login_lookup(?)",
                (rs, i) -> new LoginRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        (Boolean) rs.getObject("is_owner"),
                        (Boolean) rs.getObject("is_active")),
                email);
        if (rows.isEmpty()) throw new UsernameNotFoundException(email);
        LoginRow u = rows.get(0);
        // The owner never ends up with empty authorities, whatever user_role holds:
        // is_owner == true means every permission in the catalogue.
        // Read in the user's own tenant so it keeps working once RLS is on.
        Set<String> perms = TenantContext.callAs(u.orgId(), u.id(), () ->
                Boolean.TRUE.equals(u.isOwner())
                        ? permissions.allPermissions()
                        : permissions.permissionsOf(u.id()));
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                perms.stream().map(SimpleGrantedAuthority::new).toList()
        );
        // Add a ROLE_OWNER authority for isOwner users so @PreAuthorize("hasRole('OWNER')")
        // works at the controller layer (defense-in-depth on template CRUD etc.).
        // Non-owner users don't get this authority → they get 403 on owner-only endpoints.
        if (Boolean.TRUE.equals(u.isOwner())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
        }
        return User.withUsername(u.email())
                .password(u.passwordHash())
                .authorities(authorities)
                .disabled(!Boolean.TRUE.equals(u.isActive()))
                .build();
    }
}
