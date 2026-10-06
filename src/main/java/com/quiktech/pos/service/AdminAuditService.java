package com.quiktech.pos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quiktech.pos.dto.response.admin.AdminAuditPage;
import com.quiktech.pos.dto.response.admin.AdminAuditResponse;
import com.quiktech.pos.entity.AuditLog;
import com.quiktech.pos.repository.AuditLogRepository;
import com.quiktech.pos.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private static final Set<String> ACTIONS = Set.of("ADMIN_PLAN_CHANGED", "ADMIN_INVOICE_CONFIRMED",
            "ADMIN_INVOICE_REJECTED", "ADMIN_USER_STATUS_CHANGED", "ADMIN_USER_DELETED",
            "ADMIN_PAYMENT_ACCOUNT_CREATED", "ADMIN_PAYMENT_ACCOUNT_UPDATED",
            "ADMIN_PAYMENT_ACCOUNT_ACTIVATED", "ADMIN_PAYMENT_ACCOUNT_ARCHIVED");
    private final AuditLogRepository repository;
    private final ObjectMapper mapper;

    // Participate in the mutation transaction: a failed/rolled-back action must not leave a success log.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Long entityId, Long businessId,
                       String reason, Object before, Object after) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal actor)) {
            throw new AccessDeniedException("Administrator identity required for audit");
        }
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unknown admin action");
        try {
            var metadata = mapper.createObjectNode();
            metadata.put("actorName", actor.username());
            metadata.put("reason", reason == null || reason.isBlank() ? null : reason.trim());
            metadata.set("state", mapper.valueToTree(after));
            repository.save(AuditLog.builder().userId(actor.userId()).businessId(businessId)
                    .action(action).entityType(entityType).entityId(entityId)
                    .oldValue(mapper.writeValueAsString(before)).newValue(mapper.writeValueAsString(metadata)).build());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize admin audit", ex);
        }
    }

    @Transactional(readOnly = true)
    public AdminAuditPage search(Long beforeId, Long businessId, Long actorId, String action, int size) {
        if (size < 1 || size > 100 || (beforeId != null && beforeId <= 0)
                || (businessId != null && businessId <= 0) || (actorId != null && actorId <= 0)
                || (action != null && !ACTIONS.contains(action))) {
            throw new IllegalArgumentException("Invalid audit filter");
        }
        var rows = repository.findAll((root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            filters.add(root.get("action").in(ACTIONS));
            if (beforeId != null) filters.add(cb.lessThan(root.get("id"), beforeId));
            if (businessId != null) filters.add(cb.equal(root.get("businessId"), businessId));
            if (actorId != null) filters.add(cb.equal(root.get("userId"), actorId));
            if (action != null) filters.add(cb.equal(root.get("action"), action));
            return cb.and(filters.toArray(Predicate[]::new));
        }, PageRequest.of(0, size + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        var content = rows.stream().limit(size).map(this::toResponse).toList();
        Long next = rows.size() > size ? content.getLast().id() : null;
        return new AdminAuditPage(content, next);
    }

    private AdminAuditResponse toResponse(AuditLog row) {
        try {
            var metadata = mapper.readTree(row.getNewValue());
            return new AdminAuditResponse(row.getId(), row.getUserId(), metadata.path("actorName").asText(),
                    row.getBusinessId(), row.getAction(), row.getEntityType(), row.getEntityId(),
                    metadata.path("reason").asText(null), mapper.readTree(row.getOldValue()), metadata.path("state"), row.getCreatedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot read admin audit", ex);
        }
    }
}
