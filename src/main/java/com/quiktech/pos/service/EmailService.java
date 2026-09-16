package com.quiktech.pos.service;

import com.quiktech.pos.entity.enums.SubscriptionPlan;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class EmailService {

    @Value("${spring.mail.from:noreply@quiktech.app}")
    private String fromAddress;

    // Optional — null khi spring.mail.host chưa được cấu hình
    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Async
    public void sendInvoiceConfirmed(String to, String businessName,
                                     SubscriptionPlan plan, BigDecimal amount, Instant expiresAt) {
        if (!isConfigured(to)) return;

        String subject = "Thanh toán xác nhận - Gói " + plan.name();
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#2e7d32">Thanh toán đã được xác nhận</h2>
                <p>Xin chào <strong>%s</strong>,</p>
                <p>Thanh toán nâng cấp gói dịch vụ của bạn đã được xác nhận thành công.</p>
                <table style="border-collapse:collapse;width:100%%;max-width:400px">
                  <tr><td style="padding:8px;border:1px solid #ddd"><strong>Gói</strong></td>
                      <td style="padding:8px;border:1px solid #ddd">%s</td></tr>
                  <tr><td style="padding:8px;border:1px solid #ddd"><strong>Số tiền</strong></td>
                      <td style="padding:8px;border:1px solid #ddd">%s VND</td></tr>
                  <tr><td style="padding:8px;border:1px solid #ddd"><strong>Hết hạn</strong></td>
                      <td style="padding:8px;border:1px solid #ddd">%s</td></tr>
                </table>
                <p>Cảm ơn bạn đã sử dụng dịch vụ QuikTech POS!</p>
                </body></html>
                """.formatted(businessName, plan.name(), amount.toPlainString(), formatDate(expiresAt));

        send(to, subject, body);
    }

    @Async
    public void sendInvoiceRejected(String to, String businessName, String adminNote) {
        if (!isConfigured(to)) return;

        String subject = "Thanh toán bị từ chối";
        String note = (adminNote != null && !adminNote.isBlank()) ? adminNote : "(không có ghi chú)";
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#c62828">Thanh toán bị từ chối</h2>
                <p>Xin chào <strong>%s</strong>,</p>
                <p>Yêu cầu nâng cấp gói dịch vụ của bạn đã bị từ chối.</p>
                <p><strong>Lý do:</strong> %s</p>
                <p>Vui lòng liên hệ hỗ trợ hoặc tạo lại yêu cầu thanh toán mới.</p>
                </body></html>
                """.formatted(businessName, note);

        send(to, subject, body);
    }

    @Async
    public void sendSubscriptionExpiryWarning(String to, String businessName, Instant expiresAt) {
        if (!isConfigured(to)) return;

        String subject = "Gói dịch vụ sắp hết hạn";
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <h2 style="color:#e65100">Gói dịch vụ sắp hết hạn</h2>
                <p>Xin chào <strong>%s</strong>,</p>
                <p>Gói dịch vụ của bạn sẽ hết hạn vào ngày <strong>%s</strong>.</p>
                <p>Vui lòng gia hạn để tiếp tục sử dụng đầy đủ tính năng.</p>
                </body></html>
                """.formatted(businessName, formatDate(expiresAt));

        send(to, subject, body);
    }

    private boolean isConfigured(String to) {
        if (mailSender == null) {
            log.debug("Email not configured (spring.mail.host not set) — skipping email");
            return false;
        }
        if (to == null || to.isBlank()) {
            log.debug("No recipient email address — skipping email");
            return false;
        }
        return true;
    }

    private void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    private String formatDate(Instant instant) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(instant);
    }
}
