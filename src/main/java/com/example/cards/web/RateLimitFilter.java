package com.example.cards.web;

import com.example.cards.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects every endpoint (API and actuator) from clients that overuse the service.
 * Keyed by remote address. Behind a load balancer, configure the container to resolve the real client IP
 * (server.forward-headers-strategy=native) rather than trusting X-Forwarded-For blindly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final AppProperties props;
    private final MeterRegistry registry;

    public RateLimitFilter(RateLimiter limiter, AppProperties props, MeterRegistry registry) {
        this.limiter = limiter;
        this.props = props;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.rateLimit().enabled() || limiter.tryAcquire(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        registry.counter("ratelimit.rejected").increment();
        log.warn("Rate limit exceeded for client {}", request.getRemoteAddr());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"code\":\"RATE_LIMITED\",\"detail\":\"Too many requests, slow down and retry later\"}");
    }
}
