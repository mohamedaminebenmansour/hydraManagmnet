package tn.hydra.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op {@link MailService}: used when SMTP is not configured. {@link #isEnabled}
 * returns false and {@link #send} logs one INFO line (without the content) so the
 * log shows that an email would have been sent.
 */
public class MailServiceDisabled implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceDisabled.class);

    @Override
    public boolean isEnabled() { return false; }

    @Override
    public void send(String to, String subject, String html) {
        log.info("[MAIL] disabled — would send to={} subject={}", mask(to), subject);
    }

    private static String mask(String email) {
        if (email == null) return "?";
        int at = email.indexOf('@');
        return at < 0 ? "?@?" : "***@" + email.substring(at + 1);
    }
}
