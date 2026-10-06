package com.quiktech.pos.config.migration;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.Properties;

/** One-time import only. Runtime payment services never read the legacy properties. */
@Component
@RequiredArgsConstructor
public class V12__Import_subscription_payment_account extends BaseJavaMigration {
    private final Environment environment;

    @Override
    public void migrate(Context context) throws Exception {
        var legacy = new Properties();
        try (var input = new ClassPathResource("db/legacy-subscription-bank.properties").getInputStream()) {
            legacy.load(input);
        }
        String bank = value(legacy, "bank-name");
        String number = value(legacy, "account-number");
        String holder = value(legacy, "account-holder");
        String branch = value(legacy, "branch");
        if (bank.isBlank() || number.isBlank() || holder.isBlank()) return;

        var connection = context.getConnection();
        long id;
        try (var insert = connection.prepareStatement("""
                INSERT INTO subscription_payment_accounts(label, bank_name, account_number, account_holder, branch)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """)) {
            insert.setString(1, "Imported receiving account");
            insert.setString(2, bank);
            insert.setString(3, number);
            insert.setString(4, holder);
            insert.setString(5, branch);
            try (var result = insert.executeQuery()) { result.next(); id = result.getLong(1); }
        }
        try (var activate = connection.prepareStatement("UPDATE subscription_payment_settings SET active_account_id = ? WHERE id = 1")) {
            activate.setLong(1, id);
            activate.executeUpdate();
        }
        // Pending invoices previously displayed the current configuration. Preserve that destination.
        // Finished invoices have no trustworthy historical bank details, so leave their snapshot empty.
        try (var invoices = connection.prepareStatement("""
                UPDATE subscription_invoices SET payment_account_id = ?, payment_bank_name = ?,
                payment_account_number = ?, payment_account_holder = ?, payment_branch = ?
                WHERE status = 'PENDING' AND payment_bank_name IS NULL
                """)) {
            invoices.setLong(1, id);
            invoices.setString(2, bank);
            invoices.setString(3, number);
            invoices.setString(4, holder);
            invoices.setString(5, branch);
            invoices.executeUpdate();
        }
    }

    private String value(Properties legacy, String name) {
        String key = "subscription.payment." + name;
        return environment.getProperty(key, legacy.getProperty(key, "")).trim();
    }
}
