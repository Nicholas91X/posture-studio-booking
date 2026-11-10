package com.posturestudio.booking.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

// Builds the payment accounts from the configuration (guide 30, part 3): a wrong account stops the startup
@Configuration(proxyBeanMethods = false)
class PaymentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfiguration.class);

    @Bean
    PaymentAccounts paymentAccounts(Environment environment,
                                    @Value("${payment.accounts:}") String names,
                                    @Value("${payment.live:false}") boolean live) {
        PaymentAccounts accounts = PaymentAccounts.from(names, live, environment::getProperty);
        // names only, never a key
        log.info("Payment accounts ({} mode): {}", live ? "LIVE" : "test",
                accounts.names().isEmpty() ? "none" : String.join(", ", accounts.names()));
        return accounts;
    }
}
