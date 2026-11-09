package com.posturestudio.booking.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the @Scheduled jobs (guide 21), of every module: BookingJobs, and LoginCodeJobs (guide 24, 6.5).
 * On by default: without the jobs, expired holds and unanswered requests would never be closed, and old
 * access codes never deleted. booking.jobs.enabled=false switches them all off, for example on a second
 * instance of the application (guide 21, part 5). The name stays the one of D-047.
 * Spring Boot gives the jobs a single thread: they run one after the other, never together.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "booking.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
