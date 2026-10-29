package tn.hydra.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class HydraUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final PermissionService permissions;

    public HydraUserDetailsService(AppUserRepository users, PermissionService permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser u = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(email));
        // The owner never ends up with empty authorities, whatever user_role holds:
        // is_owner == true means every permission in the catalogue.
        Set<String> perms = Boolean.TRUE.equals(u.getIsOwner())
                ? permissions.allPermissions()
                : permissions.permissionsOf(u.getId());
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                perms.stream().map(SimpleGrantedAuthority::new).toList()
        );
        // Add a ROLE_OWNER authority for isOwner users so @PreAuthorize("hasRole('OWNER')")
        // works at the controller layer (defense-in-depth on template CRUD etc.).
        // Non-owner users don't get this authority → they get 403 on owner-only endpoints.
        if (Boolean.TRUE.equals(u.getIsOwner())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
        }
        return User.withUsername(u.getEmail())
                .password(u.getPasswordHash())
                .authorities(authorities)
                .disabled(!Boolean.TRUE.equals(u.getIsActive()))
                .build();
    }
}