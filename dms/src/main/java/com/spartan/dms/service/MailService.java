package com.spartan.dms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    /**
     * Sends the password-reset email. Any failure here (bad SMTP creds,
     * mail server unreachable, etc.) is logged and swallowed rather than
     * propagated — a mail outage should never turn into a 500 that also
     * leaks whether an email address exists in the system.
     */
    public void sendPasswordResetEmail(String toEmail, String fullName, String resetLink) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (fromAddress != null && !fromAddress.isBlank()) {
                message.setFrom(fromAddress);
            }
            message.setTo(toEmail);
            message.setSubject("Reset your Brisk DMS password");
            message.setText(
                    "Hi " + (fullName != null ? fullName : "there") + ",\n\n" +
                            "We received a request to reset your Brisk DMS password. Click the link " +
                            "below to set a new password. This link expires in 30 minutes.\n\n" +
                            resetLink + "\n\n" +
                            "If you didn't request this, you can safely ignore this email.\n\n" +
                            "— Brisk DMS"
            );
            mailSender.send(message);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}: {}", toEmail, ex.getMessage());
        }
    }
}