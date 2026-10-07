package com.quiktech.pos.config.seed;

import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.function.Supplier;

/** Bộ đếm riêng cho mỗi lần chạy; chỉ báo hoàn tất khi transaction đã commit. */
final class SeedSummary {
    private int created;
    private int skipped;

    void record(boolean wasCreated) {
        if (wasCreated) created++;
        else skipped++;
    }

    <T> T getOrCreate(Optional<T> existing, Supplier<T> create) {
        if (existing.isPresent()) {
            record(false);
            return existing.get();
        }
        T saved = create.get();
        record(true);
        return saved;
    }

    void logAfterCommit(Logger log, String name) {
        logAfterCommit(log, name, "");
    }

    void logAfterCommit(Logger log, String name, String details) {
        int createdCount = created;
        int skippedCount = skipped;
        String suffix = details.isEmpty() ? "" : ", " + details;
        Runnable writeLog = () -> log.info("{} seed completed: created={}, skipped={}{}",
                name, createdCount, skippedCount, suffix);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeLog.run();
                }
            });
        } else {
            writeLog.run();
        }
    }
}
