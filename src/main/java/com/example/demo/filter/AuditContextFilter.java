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

/**
 * 审计上下文过滤器
 *
 * <p>每次请求入口时从 HTTP 请求头提取 traceId、userId、clientIp，
 * 并写入 MDC，以便后续审计切面和日志框架使用。
 * 请求结束后清理 MDC，防止 Tomcat 线程池中的数据污染。
 */
@Component
public class AuditContextFilter extends OncePerRequestFilter {

    private static final String HEADER_TRACE_ID  = "X-Trace-Id";
    private static final String HEADER_USER_ID   = "X-User-Id";
    private static final String HEADER_CLIENT_IP = "Client-IP";

    private static final String MDC_TRACE_ID  = "traceId";
    private static final String MDC_USER_ID   = "userId";
    private static final String MDC_CLIENT_IP = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            MDC.put(MDC_TRACE_ID,  resolveTraceId(request));
            MDC.put(MDC_CLIENT_IP, resolveClientIp(request));

            String userId = request.getHeader(HEADER_USER_ID);
            if (StringUtils.hasText(userId)) {
                MDC.put(MDC_USER_ID, userId);
            }

            filterChain.doFilter(request, response);
        } finally {
            // 清理 MDC，防止 Tomcat 线程池复用时数据污染
            MDC.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        return StringUtils.hasText(traceId)
                ? traceId
                : UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String clientIp = request.getHeader(HEADER_CLIENT_IP);
        return StringUtils.hasText(clientIp) ? clientIp : request.getRemoteAddr();
    }
}
