package com.maaitlunghau.spring_boot_blueprint.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.maaitlunghau.spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.spring_boot_blueprint.config.RateLimitConfig;
import com.maaitlunghau.spring_boot_blueprint.config.RateLimitConfig.RateLimitRule;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimitConfig rateLimitConfig;
    private final LettuceBasedProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
        RateLimitConfig rateLimitConfig,
        LettuceBasedProxyManager<byte[]> proxyManager,
        ObjectMapper objectMapper
    ) {
        this.rateLimitConfig = rateLimitConfig;
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitRule rule = resolveRule(request);
        String key = "rate-limit:%s:%s:%s".formatted(request.getRemoteAddr(), rule.httpMethod(), rule.pathPattern());

        BucketProxy bucket = proxyManager.builder()
            .withImplicitConfigurationReplacement(rule.configVersion(), TokensInheritanceStrategy.RESET)
            .build(key.getBytes(StandardCharsets.UTF_8), () -> bucketConfiguration(rule));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        response.setStatus(TOO_MANY_REQUESTS);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
            objectMapper.writeValueAsString(
                ApiResponse.message(TOO_MANY_REQUESTS, "Too many requests, please try again later")
            )
        );
    }

    private RateLimitRule resolveRule(HttpServletRequest request) {
        List<RateLimitRule> rules = rateLimitConfig.getRules();

        for (RateLimitRule rule : rules) {
            boolean pathMatches = PATH_MATCHER.match(rule.pathPattern(), request.getRequestURI());
            boolean methodMatches = rule.httpMethod().equalsIgnoreCase(request.getMethod());
            
            if (pathMatches && methodMatches) {
                return rule;
            }
        }

        return rateLimitConfig.getDefaultRule();
    }

    private BucketConfiguration bucketConfiguration(RateLimitRule rule) {
        Bandwidth bandwidth = Bandwidth.builder()
            .capacity(rule.capacity())
            .refillGreedy(rule.capacity(), rule.refillPeriod())
            .build();

        return BucketConfiguration.builder()
            .addLimit(bandwidth)
            .build();
    }
}
