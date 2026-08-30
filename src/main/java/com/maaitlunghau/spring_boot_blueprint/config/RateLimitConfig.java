package com.maaitlunghau.spring_boot_blueprint.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.maaitlunghau.spring_boot_blueprint.filter.RateLimitFilter;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RateLimitConfig {

    // configVersion: bump this whenever capacity/refillPeriod is deliberately changed for a rule —
    // Bucket4j only replaces an already-persisted Redis bucket's config when it sees a higher
    // version than what's stored, otherwise it keeps using whatever config the bucket was first
    // created with (see RateLimitFilter's withImplicitConfigurationReplacement).
    public record RateLimitRule(
        String pathPattern,
        String httpMethod,
        int capacity,
        Duration refillPeriod,
        long configVersion
    ) {}

   private static final List<RateLimitRule> SENSITIVE_RULES = List.of(
        new RateLimitRule(
            "/api/users",
            "POST",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/ban",
            "PATCH",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/unban",
            "PATCH",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/avatar",
            "POST",
            3,
            Duration.ofMinutes(1),
            2
        )
    );

    private static final RateLimitRule DEFAULT_RULE = new RateLimitRule("/**", "*", 100, Duration.ofMinutes(1), 1);

    public List<RateLimitRule> getRules() {
        return SENSITIVE_RULES;
    }

    public RateLimitRule getDefaultRule() {
        return DEFAULT_RULE;
    }

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        RedisURI.Builder builder = RedisURI.Builder.redis(redisHost, redisPort);
        // CI's redis service has no password (see docker-compose.yml/ci.yml notes) — sending
        // AUTH with an empty password would be rejected by a server that requires none.
        if (redisPassword != null && !redisPassword.isBlank()) {
            builder.withPassword(redisPassword.toCharArray());
        }
        
        return RedisClient.create(builder.build());
    }

    @Bean
    public LettuceBasedProxyManager<byte[]> lettuceBasedProxyManager(RedisClient rateLimitRedisClient) {
        ClientSideConfig clientSideConfig = ClientSideConfig.getDefault()
            .withExpirationAfterWriteStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10))
            );

        return LettuceBasedProxyManager.builderFor(rateLimitRedisClient)
            .withClientSideConfig(clientSideConfig)
            .build();
    }

    @Bean
    public RateLimitFilter rateLimitFilter(LettuceBasedProxyManager<byte[]> lettuceBasedProxyManager, ObjectMapper objectMapper) {
        return new RateLimitFilter(this, lettuceBasedProxyManager, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);

        return registration;
    }
}
