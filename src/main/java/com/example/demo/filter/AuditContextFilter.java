package com.example.demo.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader("X-Trace-Id");
            if (!StringUtils.hasText(traceId)) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            
            String userId = request.getHeader("X-User-Id");
            String clientIp = request.getHeader("Client-IP");
            if (!StringUtils.hasText(clientIp)) {
                clientIp = request.getRemoteAddr();
            }

            MDC.put("traceId", traceId);
            if (StringUtils.hasText(userId)) MDC.put("userId", userId);
            MDC.put("clientIp", clientIp);
            
            filterChain.doFilter(request, response);
        } finally {
            // Prevent Tomcat thread pool pollution
            MDC.clear();
        }
    }
}
