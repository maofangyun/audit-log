package com.example.demo.aspect;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import com.example.demo.log.AuditMessage;
import com.example.demo.service.AuditItemService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.context.expression.AnnotatedElementKey;

/**
 * 审计日志切面 —— Claim Check 模式集成版
 *
 * <p>
 * 支持注解合并：优先使用子类/方法上的注解属性，若为默认值则尝试从父类/抽象类获取。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("AUDIT_LOG_NAME");

    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final ObjectMapper objectMapper;
    private final AuditItemService auditItemService;
    private final Executor auditAsyncPool;
    private final SnowflakeIdWorker snowflakeIdWorker;
    private final ApplicationContext applicationContext;

    public AuditLogAspect(AuditItemService auditItemService,
            @Qualifier("auditAsyncPool") Executor auditAsyncPool,
            ObjectMapper objectMapper,
            SnowflakeIdWorker snowflakeIdWorker,
            ApplicationContext applicationContext) {
        this.auditItemService = auditItemService;
        this.auditAsyncPool = auditAsyncPool;
        this.objectMapper = objectMapper;
        this.snowflakeIdWorker = snowflakeIdWorker;
        this.applicationContext = applicationContext;
    }

    @Around("@annotation(com.example.demo.log.AuditLog) || @within(com.example.demo.log.AuditLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取合并后的注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
        AuditLogData auditLog = resolveAuditLog(method, joinPoint.getTarget().getClass());

        if (auditLog == null) {
            return joinPoint.proceed();
        }

        // 2. 生成 traceId（若 MDC 已有则复用）
        String traceId = MDC.get("traceId");
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String userId = MDC.get("userId");

        // 3. 构建 SpEL 上下文
        Object[] args = joinPoint.getArgs();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        StandardEvaluationContext spelContext = SpElUtils.buildContext(paramNames, args);
        spelContext.setBeanResolver(new BeanFactoryResolver(applicationContext));

        // 4. 解析注解属性
        String resourceId = SpElUtils.evalString(auditLog.resourceIdSpEL, spelContext, "PARSE_ERROR");

        Object businessKey = null;
        if (StringUtils.hasText(auditLog.businessKeySpEL)) {
            businessKey = SpElUtils.eval(auditLog.businessKeySpEL, spelContext);
        }

        if (!StringUtils.hasText(userId)) {
            userId = SpElUtils.evalString(auditLog.userIdSpEL, spelContext, null);
        }

        Object oldObjectSpELResult = SpElUtils.eval(auditLog.oldObjectSpEL, spelContext);

        // 5. 执行目标方法
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
            // 6. 在方法执行后读取 AuditContextHolder（Controller 在方法体内调用 setOldObject）
            Object oldObject = oldObjectSpELResult;
            if (oldObject == null) {
                oldObject = AuditContextHolder.getOldObject();
            }

            // 7. 异步提交审计任务
            final String finalTraceId = traceId;
            final String finalUserId = userId;
            final String finalResourceId = resourceId;
            final Object finalBusinessKey = businessKey;
            final String finalStatus = status;
            final String finalErrorMsg = errorMsg;
            final Object finalOldObject = oldObject;
            final Object finalResult = result;
            final AuditLogData finalAuditLog = auditLog;

            auditAsyncPool.execute(() -> processAuditAsync(
                    finalAuditLog, spelContext,
                    finalTraceId, finalUserId, finalResourceId, finalBusinessKey,
                    finalStatus, finalErrorMsg,
                    finalOldObject, finalResult));
            AuditContextHolder.clear();
        }
    }

    // 缓存解析过的注解配置，提升高并发性能 (Method + TargetClass 作为联合 Key)
    private final Map<AnnotatedElementKey, AuditLogData> auditLogCache = new ConcurrentHashMap<>();

    private AuditLogData resolveAuditLog(Method method, Class<?> targetClass) {
        AnnotatedElementKey cacheKey = new AnnotatedElementKey(method, targetClass);
        return auditLogCache.computeIfAbsent(cacheKey, key -> {
            List<MergedAnnotation<AuditLog>> annotations = new ArrayList<>();
            // 顺序：方法继承链 -> 类继承链 (由近及远)
            MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .stream(AuditLog.class).forEach(annotations::add);
            MergedAnnotations.from(targetClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .stream(AuditLog.class).forEach(annotations::add);

            if (annotations.isEmpty()) {
                return null;
            }

            AuditLogData data = new AuditLogData();
            for (MergedAnnotation<AuditLog> ann : annotations) {
                // 利用 Spring 的 StringUtils.hasText()，当属性不存在或为空字符串时，自动采用下一个更外层注解的属性值
                if (!StringUtils.hasText(data.action))
                    data.action = ann.getString("action");
                if (!StringUtils.hasText(data.resourceType))
                    data.resourceType = ann.getString("resourceType");
                if (!StringUtils.hasText(data.resourceIdSpEL))
                    data.resourceIdSpEL = ann.getString("resourceIdSpEL");
                if (!StringUtils.hasText(data.businessKeySpEL))
                    data.businessKeySpEL = ann.getString("businessKeySpEL");
                if (!StringUtils.hasText(data.userIdSpEL))
                    data.userIdSpEL = ann.getString("userIdSpEL");
                if (!StringUtils.hasText(data.oldObjectSpEL))
                    data.oldObjectSpEL = ann.getString("oldObjectSpEL");
                if (!StringUtils.hasText(data.newObjectSpEL))
                    data.newObjectSpEL = ann.getString("newObjectSpEL");
            }
            return data;
        });
    }

    private static class AuditLogData {
        String action;
        String resourceType;
        String resourceIdSpEL = "";
        String businessKeySpEL = "";
        String userIdSpEL = "";
        String oldObjectSpEL = "";
        String newObjectSpEL = "";
    }

    private void processAuditAsync(AuditLogData auditLog,
            StandardEvaluationContext spelContext,
            String traceId, String userId, String resourceId, Object businessKey,
            String status, String errorMsg,
            Object oldObject, Object methodResult) {
        try {
            SpElUtils.setVariable(spelContext, "result", methodResult);
            Object newObject = SpElUtils.eval(auditLog.newObjectSpEL, spelContext);

            // 1. 生成主记录 ID（子表通过此 ID 关联，无外键约束，顺序无关）
            long auditLogId = snowflakeIdWorker.nextId();

            // 2. 构建主记录（纯元数据，无 details / isLargePayload）
            AuditMessage msg = AuditMessage.builder()
                    .id(auditLogId)
                    .traceId(traceId)
                    .userId(userId)
                    .action(auditLog.action)
                    .resourceType(auditLog.resourceType)
                    .resourceId(resourceId)
                    .businessKey(businessKey != null ? businessKey : resourceId)
                    .status(status)
                    .build();

            // 3. 落盘主记录（AUDIT_LOGGER → 日志文件 → Vector → audit_logs）
            AUDIT_LOGGER.info(objectMapper.writeValueAsString(msg));

            // 4. 写子项日志（AUDIT_ITEMS_LOGGER → 日志文件 → Vector → audit_log_items）
            //    无外键约束，before/after 均为 null 时静默跳过
            auditItemService.saveItems(auditLogId, oldObject, "before", traceId);
            auditItemService.saveItems(auditLogId, newObject, "after",  traceId);

        } catch (Exception e) {
            AUDIT_LOGGER.error("[AuditLogAspect] 审计任务处理异常: {}", e.getMessage(), e);
        }
    }
}
