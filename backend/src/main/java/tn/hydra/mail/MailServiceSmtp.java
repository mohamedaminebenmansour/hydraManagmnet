package tn.hydra.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.UUID;

/**
 * Sends real emails through Spring's {@link JavaMailSender}. A send failure is
 * logged at WARN with a reference and never propagates — the user action that
 * triggered the email already committed.
 */
public class MailServiceSmtp implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceSmtp.class);

    private final JavaMailSender sender;
    private final String from;

    public MailServiceSmtp(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public void send(String to, String subject, String html) {
        String ref = UUID.randomUUID().toString().substring(0, 8);
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(html, true);
            sender.send(msg);
            log.info("[MAIL] sent ref={} to={} subject={}", ref, mask(to), subject);
        } catch (RuntimeException | MessagingException e) {
            log.warn("[MAIL] FAILED ref={} to={} subject={} — {}", ref, mask(to), subject, e.getMessage());
        }
    }

    /** Mask the recipient for privacy in logs (show only the domain). */
    private static String mask(String email) {
        if (email == null) return "?";
        int at = email.indexOf('@');
        return at < 0 ? "?@?" : "***@" + email.substring(at + 1);
    }
}
