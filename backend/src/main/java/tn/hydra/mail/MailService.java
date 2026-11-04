package tn.hydra.mail;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mail sender (U2). Two implementations:
 * <ul>
 *   <li>{@link MailServiceSmtp} — used when {@code spring.mail.host} AND {@code hydra.mail.from} are set.</li>
 *   <li>{@link MailServiceDisabled} — used otherwise (logs one INFO line, never sends).</li>
 * </ul>
 *
 * <p>{@link #sendAfterCommit} registers a {@link TransactionSynchronization} so the
 * email is sent AFTER the surrounding transaction commits — a mail failure never
 * breaks the user action (logged at WARN with a reference). When no transaction is
 * active, the email is sent immediately.
 */
public interface MailService {

    /** True when SMTP is configured and {@link #send} actually sends. */
    boolean isEnabled();

    /**
     * Send an HTML email. Never throws: a send failure is logged at WARN.
     * @param to the recipient email
     * @param subject the subject (UTF-8)
     * @param html the HTML body
     */
    void send(String to, String subject, String html);

    /**
     * Like {@link #send} but deferred to after-commit so a mail failure never
     * rolls back the business transaction. When no transaction is active,
     * sends immediately.
     */
    default void sendAfterCommit(String to, String subject, String html) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(to, subject, html);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(to, subject, html);
            }
        });
    }
}
