package com.example.licensing.generator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * Anyone who can call this service can mint licenses, so everything except
 * the health probes requires an operator login (HTTP Basic; put the service
 * behind TLS - an ingress or a port-forward - never plain HTTP over a network).
 * Managing signing keys ({@code /api/v1/keys/**}) additionally needs the
 * ADMIN role ({@code admin: true} on the operator).
 *
 * <p>CSRF protection is off because the API is stateless (no session cookie)
 * and every state-changing endpoint only accepts {@code application/json}:
 * a cross-site page cannot send such a request without a CORS preflight,
 * which this service does not allow.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/keys", "/api/v1/keys/**").hasRole("ADMIN")
                        .anyRequest().hasRole("OPERATOR"))
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Operators from {@code license.generator.operators}; their login names go into the audit log. */
    @Bean
    UserDetailsService operators(GeneratorProperties properties) {
        List<GeneratorProperties.Operator> configured =
                properties.operators() == null ? List.of() : properties.operators();
        if (configured.isEmpty()) {
            throw new IllegalStateException("no operators configured (license.generator.operators) - "
                    + "nobody could log in to issue licenses");
        }
        List<UserDetails> users = new ArrayList<>();
        for (GeneratorProperties.Operator operator : configured) {
            if (operator.password() == null || !operator.password().startsWith("{")) {
                throw new IllegalStateException("password for operator '" + operator.username()
                        + "' must be an encoded hash such as {bcrypt}$2a$..., not plain text");
            }
            users.add(User.withUsername(operator.username())
                    .password(operator.password())
                    .roles(operator.isAdmin() ? new String[]{"OPERATOR", "ADMIN"} : new String[]{"OPERATOR"})
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
