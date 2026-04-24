package com.example.demo.aspect;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import com.example.demo.log.AuditMessage;
import com.example.demo.service.ClaimCheckService;
import com.example.demo.util.SnowflakeIdWorker;
import com.example.demo.util.SpElUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 审计日志切面 —— Claim Check 模式集成版
 *
 * <p>处理流程：
 * <ol>
 *   <li>AOP 拦截 {@link AuditLog} 注解方法</li>
 *   <li>同步执行目标方法</li>
 *   <li>提交异步任务至 auditAsyncPool</li>
 *   <li>异步任务内：Claim Check 决策 → 构造 {@link AuditMessage} → 落盘</li>
 * </ol>
 *
 * <p>SpEL 解析委托 {@link SpElUtils}，ID 生成委托 {@link SnowflakeIdWorker}。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("AUDIT_LOG_NAME");

    private final StandardReflectionParameterNameDiscoverer paramNameDiscoverer =
            new StandardReflectionParameterNameDiscoverer();

    private final ObjectMapper objectMapper;
    private final ClaimCheckService claimCheckService;
    private final Executor auditAsyncPool;
    private final SnowflakeIdWorker snowflakeIdWorker;

    public AuditLogAspect(ClaimCheckService claimCheckService,
                          @Qualifier("auditAsyncPool") Executor auditAsyncPool,
                          ObjectMapper objectMapper,
                          SnowflakeIdWorker snowflakeIdWorker) {
        this.claimCheckService = claimCheckService;
        this.auditAsyncPool = auditAsyncPool;
        this.objectMapper = objectMapper;
        this.snowflakeIdWorker = snowflakeIdWorker;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        // 1. 生成 traceId（若 MDC 已有则复用）
        String traceId = MDC.get("traceId");
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String userId = MDC.get("userId");

        // 2. 构建 SpEL 上下文
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        StandardEvaluationContext spelContext = SpElUtils.buildContext(paramNames, args);

        // 3. 解析注解属性
        String resourceId = SpElUtils.evalString(auditLog.resourceIdSpEL(), spelContext, "PARSE_ERROR");

        // userId：优先 MDC，其次注解 SpEL
        if (!StringUtils.hasText(userId)) {
            userId = SpElUtils.evalString(auditLog.userIdSpEL(), spelContext, null);
        }

        // oldObject：优先注解 SpEL，其次 AuditContextHolder
        Object oldObject = SpElUtils.eval(auditLog.oldObjectSpEL(), spelContext);
        if (oldObject == null) {
            oldObject = AuditContextHolder.getOldObject();
        }

        // 4. 执行目标方法
        String status = "SUCCESS";
        String errorMsg = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            status = "FAIL";
            errorMsg = t.getMessage();
            throw t;
        } finally {
            // 5. 异步提交审计任务
            final String finalTraceId    = traceId;
            final String finalUserId     = userId;
            final String finalResourceId = resourceId;
            final String finalStatus     = status;
            final String finalErrorMsg   = errorMsg;
            final Object finalOldObject  = oldObject;
            final Object finalResult     = result;

            auditAsyncPool.execute(() ->
                processAuditAsync(
                    auditLog, spelContext,
                    finalTraceId, finalUserId, finalResourceId,
                    finalStatus, finalErrorMsg,
                    finalOldObject, finalResult
                )
            );
            AuditContextHolder.clear();
        }
    }

    /**
     * 异步审计处理核心：Claim Check 决策 + 构造消息 + 落盘
     */
    private void processAuditAsync(AuditLog auditLog,
                                   StandardEvaluationContext spelContext,
                                   String traceId, String userId, String resourceId,
                                   String status, String errorMsg,
                                   Object oldObject, Object methodResult) {
        try {
            // 解析 newObject（方法执行后快照，可通过 #result 引用返回值）
            SpElUtils.setVariable(spelContext, "result", methodResult);
            Object newObject = SpElUtils.eval(auditLog.newObjectSpEL(), spelContext);

            // Claim Check 决策
            Object detailsPayload;
            boolean isLargePayload;
            Map<String, Object> pointer = claimCheckService.processDetailsPayload(traceId, oldObject, newObject);
            if (pointer != null) {
                isLargePayload = true;
                detailsPayload = pointer;
            } else {
                isLargePayload = false;
                Map<String, Object> inline = new HashMap<>();
                if (oldObject != null) inline.put("before", oldObject);
                if (newObject != null) inline.put("after",  newObject);
                if (StringUtils.hasText(errorMsg)) inline.put("error", errorMsg);
                detailsPayload = inline.isEmpty() ? null : inline;
            }

            // 构造 AuditMessage 并落盘
            AuditMessage msg = AuditMessage.builder()
                    .id(snowflakeIdWorker.nextId())
                    .traceId(traceId)
                    .userId(userId)
                    .action(auditLog.action())
                    .resourceType(auditLog.resourceType())
                    .resourceId(resourceId)
                    .businessId(resourceId)
                    .status(status)
                    .isLargePayload(isLargePayload)
                    .details(detailsPayload)
                    .build();

            AUDIT_LOGGER.info(objectMapper.writeValueAsString(msg));

        } catch (Exception e) {
            AUDIT_LOGGER.error("[AuditLogAspect] 审计任务处理异常: {}", e.getMessage(), e);
        }
    }
}
