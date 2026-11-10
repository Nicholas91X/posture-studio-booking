package com.posturestudio.booking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.posturestudio.booking.payment.FakeStripeGateway;

// The PostgreSQL container as a Spring bean: started and stopped with the test context.
// Test classes with the same configuration share the cached context, and so the container.
// Every test imports this class, so it also brings the fake Stripe (guide 31, part 10): whatever a test
// does, it never reaches the real Stripe, and no new context is needed for it
@TestConfiguration(proxyBeanMethods = false)
@Import(FakeStripeGateway.Configuration.class)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17");
    }
}
