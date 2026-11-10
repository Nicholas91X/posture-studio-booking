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
                // The only exception: Stripe's webhooks, which have no cookie and no token. Their signature
                // protects them (guide 32, part 10). A narrow prefix, so the exception covers nothing else
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/webhooks/**"))
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
                        // the phone, and so the SMS sign-in, is not for the admin (D-056): a SIM swap would
                        // open the most valuable account. Before the rule of the whole area: the first match wins
                        .requestMatchers("/api/account/phone/**")
                                .hasAnyRole(Role.CUSTOMER.name(), Role.PRACTITIONER.name())
                        // the account itself (guide 29, 8.2): whoever is signed in, of any role
                        .requestMatchers("/api/account/**").authenticated()
                        // Stripe calling us (D-062): no account, the signature of each payment account decides
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
