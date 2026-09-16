package com.quiktech.pos.service;

import com.quiktech.pos.entity.AuditLog;
import com.quiktech.pos.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    // Chạy trong thread riêng, transaction độc lập — lỗi audit không ảnh hưởng business transaction
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long userId, Long businessId, Long storeId,
                    String action, String entityType, Long entityId,
                    String oldValue, String newValue, String ip) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .userId(userId)
                    .businessId(businessId)
                    .storeId(storeId)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .ip(ip)
                    .build());
        } catch (Exception e) {
            log.error("Failed to save audit log: action={}, entityType={}, error={}", action, entityType, e.getMessage());
        }
    }
}
