package com.posturestudio.booking.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.DispatcherType;

/**
 * The chain of filters in front of every API (guide 25, parts 2, 5 and 6).
 *
 * One rule per area (D-029), and everything else closed to EVERYONE (denyAll, not authenticated): an API
 * put by mistake outside the prefixes would otherwise be open to any customer with an account (default deny).
 * Auth knows the prefixes of the other areas, not their classes: no dependency on other modules.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionAccounts sessions,
                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        // The refusals of the filters happen before any controller, so ApiExceptionHandler would not see them.
        // Handing them to the resolver of Spring MVC brings them back there: one place for every error
        // response, all as ProblemDetail (guide 25, 6.3)
        AuthenticationEntryPoint notSignedIn = (request, response, e) -> errors.resolveException(request, response, null, e);
        AccessDeniedHandler denied = (request, response, e) -> errors.resolveException(request, response, null, e);

        http
                // who is in the request: the user id in the session, the account read again every time (guide 25, 4.2)
                .securityContext(context -> context.securityContextRepository(sessions))
                // CSRF token in the XSRF-TOKEN cookie, sent back in the X-XSRF-TOKEN header (guide 25, 5.2).
                // /api/dev plays Stripe, which has no session; it disappears at step 9 with the webhook
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/dev/**"))
                // On a 401 Spring would save the request in a session, to repeat it after a form login: there is
                // no such form here, and it would create a session in the database for every anonymous refusal
                .requestCache(cache -> cache.disable())
                // no passwords, no Spring login page, no basic authentication: the sign-in is SessionController
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(notSignedIn)
                        .accessDeniedHandler(denied))
                .authorizeHttpRequests(auth -> auth
                        // internal forwards to /error: a failure inside an open API must stay what it is,
                        // not become a 401 (guide 25, 6.2)
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/panel/**").hasRole(Role.PRACTITIONER.name())
                        .requestMatchers("/api/customer/**").hasRole(Role.CUSTOMER.name())
                        .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                        // the simulated payment (D-046), which exists only with booking.simulated-payments=true
                        .requestMatchers("/api/dev/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
