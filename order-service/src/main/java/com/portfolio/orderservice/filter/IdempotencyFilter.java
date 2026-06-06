package com.portfolio.orderservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final long TTL_HOURS = 24;

    private final StringRedisTemplate redis;

    public IdempotencyFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String key = request.getHeader(IDEMPOTENCY_HEADER);
        if (key == null || !request.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }

        String cacheKey = "idempotency:orders:" + key;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            // Replay the cached 201 response — no downstream processing
            response.setStatus(HttpStatus.CREATED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(cached);
            return;
        }

        // Capture the response body so we can cache it
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);

        // Only cache successful responses; errors (402, 500) must not be cached —
        // the client should retry with a new key after addressing the failure reason.
        if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
            String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            redis.opsForValue().set(cacheKey, body, TTL_HOURS, TimeUnit.HOURS);
        }
        wrapper.copyBodyToResponse();
    }
}
