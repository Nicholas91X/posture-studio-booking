package com.posturestudio.booking.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie of Spring Session, declared here and not with server.servlet.session.cookie.*
 * (guide 25, 5.1). Spring Boot applies those properties only with a real embedded Tomcat: in the MockMvc
 * tests the cookie came out with none of its attributes, so they were not proved. Declared as a bean,
 * the same cookie is in the tests and in production, and Max-Age comes from MAX_DURATION: one place.
 */
@Configuration(proxyBeanMethods = false)
class SessionCookieConfiguration {

    @Bean
    CookieSerializer sessionCookie() {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        // SESSION says nothing about the technology (JSESSIONID would say "Java")
        cookie.setCookieName("SESSION");
        cookie.setCookiePath("/");
        // not readable by JavaScript: an injected script (XSS) cannot steal the session
        cookie.setUseHttpOnlyCookie(true);
        // only over HTTPS; browsers treat http://localhost as secure, so it works in development too
        cookie.setUseSecureCookie(true);
        // not sent with requests that change something coming from another site (second defence against CSRF)
        cookie.setSameSite("Lax");
        // kept after the browser is closed; written at the sign-in, it ends with the absolute limit
        cookie.setCookieMaxAge((int) SessionAccounts.MAX_DURATION.toSeconds());
        return cookie;
    }
}
