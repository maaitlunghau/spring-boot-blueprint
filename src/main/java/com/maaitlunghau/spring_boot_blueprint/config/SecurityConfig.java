package com.maaitlunghau.spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

import com.maaitlunghau.spring_boot_blueprint.filter.RateLimitFilter;

@Configuration
public class SecurityConfig {

    private static final String[] SWAGGER_PATHS = {
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable()) // for test (disable CSRF)
            .addFilterBefore(rateLimitFilter, DisableEncodeUrlFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(SWAGGER_PATHS).permitAll()
                .anyRequest().permitAll()) // for test (un authenticated)
            .build();
    }
}
