package com.omniflow.backend.security;

import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.repository.StoreMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("storeAccess")
@RequiredArgsConstructor
public class StoreAccessEvaluator {

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final StoreMemberRepository storeMemberRepository;

    public boolean isMember(Long storeId, Authentication authentication) {
        User user = getUser(authentication);
        if (user == null) return false;
        if (hasRole(authentication, ROLE_SUPER_ADMIN)) return true;
        return storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(user.getId(), storeId).isPresent();
    }

    public boolean isOwnerOrManager(Long storeId, Authentication authentication) {
        User user = getUser(authentication);
        if (user == null) return false;
        if (hasRole(authentication, ROLE_SUPER_ADMIN)) return true;
        return storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(user.getId(), storeId)
                .map(m -> m.getRole() == StoreRole.OWNER || m.getRole() == StoreRole.MANAGER)
                .orElse(false);
    }

    public boolean isOwner(Long storeId, Authentication authentication) {
        User user = getUser(authentication);
        if (user == null) return false;
        if (hasRole(authentication, ROLE_SUPER_ADMIN)) return true;
        return storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(user.getId(), storeId)
                .map(m -> m.getRole() == StoreRole.OWNER)
                .orElse(false);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }

    private static User getUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) return user;
        return null;
    }
}

