package com.posturestudio.booking.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Registers the limits of the access codes as a bean (guide 24, part 8)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginCodeProperties.class)
class LoginCodeConfiguration {
}
