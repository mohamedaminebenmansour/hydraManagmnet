package tn.hydra.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the right {@link MailService}: SMTP when {@code spring.mail.host} is set,
 * disabled otherwise. When host is set but {@code hydra.mail.from} is blank, the
 * SMTP impl falls back to disabled (so isEnabled() returns false).
 */
@Configuration
public class MailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.mail", name = "host")
    MailService mailServiceSmtp(JavaMailSender sender,
                                @Value("${hydra.mail.from:}") String from) {
        if (from == null || from.isBlank()) {
            return new MailServiceDisabled();
        }
        return new MailServiceSmtp(sender, from);
    }

    @Bean
    @ConditionalOnMissingBean(MailService.class)
    MailService mailServiceDisabled() {
        return new MailServiceDisabled();
    }
}
