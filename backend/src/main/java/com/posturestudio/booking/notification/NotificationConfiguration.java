package com.posturestudio.booking.notification;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Where notifications run (guide 27, parts 3 and 4): after the commit, in a thread of their own, so that
 * the response never waits for the mail server and a failed email can never reach it.
 *
 * One virtual thread per email (Java 21, JEP 444): sending is almost all waiting on the network, which is
 * what virtual threads are for. No pool: a virtual thread costs a few hundred bytes and is not reused.
 * Only this executor, not spring.threads.virtual.enabled: that switch would move Tomcat and the scheduler
 * of the jobs too, and the jobs would no longer run one after the other (guide 27, 4.2).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class NotificationConfiguration {

    static final String EXECUTOR = "notificationExecutor";

    // at shutdown, emails already handed over get this long to leave; after it, or after a crash, they are lost
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    @Bean(EXECUTOR)
    SimpleAsyncTaskExecutor notificationExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("notification-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(SHUTDOWN_WAIT.toMillis());
        return executor;
    }
}
