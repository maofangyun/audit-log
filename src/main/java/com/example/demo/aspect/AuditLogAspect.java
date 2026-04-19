package com.example.demo.aspect;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import com.example.demo.log.AuditMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Aspect
@Component
public class AuditLogAspect {

    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT_LOG_NAME");
    private final ExpressionParser parser = new SpelExpressionParser();
    private final StandardReflectionParameterNameDiscoverer discoverer = new StandardReflectionParameterNameDiscoverer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        String resourceId = "";
        JsonNode oldNode = null;
        JsonNode newNode = null;

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] params = discoverer.getParameterNames(method);
        StandardEvaluationContext context = new StandardEvaluationContext();

        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                context.setVariable(params[i], args[i]);
            }
        }

        // 1. Evaluate Resource ID
        try {
            if (StringUtils.hasText(auditLog.resourceIdSpEL())) {
                Object val = parser.parseExpression(auditLog.resourceIdSpEL()).getValue(context);
                if (val != null) {
                    resourceId = String.valueOf(val);
                }
            }
        } catch (Exception e) {
            resourceId = "PARSE_ERROR";
        }

        // 2. Evaluate Old Object before execution (freezing state)
        try {
            if (StringUtils.hasText(auditLog.oldObjectSpEL())) {
                Object val = parser.parseExpression(auditLog.oldObjectSpEL()).getValue(context);
                if (val != null) {
                    oldNode = objectMapper.valueToTree(val);
                }
            }
        } catch (Exception ignored) {
        }
        
        // Fallback: If oldNode is null, try to retrieve from AuditContextHolder
        if (oldNode == null) {
            oldNode = AuditContextHolder.getOldObject();
        }

        String status = "SUCCESS";
        String details = "";
        Object result = null;

        try {
            // Execute method
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            status = "FAIL";
            details = t.getMessage();
            throw t;
        } finally {
            try {
                // Now evaluate new object
                context.setVariable("result", result);
                if (StringUtils.hasText(auditLog.newObjectSpEL())) {
                    Object val = parser.parseExpression(auditLog.newObjectSpEL()).getValue(context);
                    if (val != null) {
                        newNode = objectMapper.valueToTree(val);
                    }
                }
                
                // Compare differences
                List<String> diffs = generateDiff(oldNode, newNode);

                AuditMessage msg = AuditMessage.builder()
                        .id(com.example.demo.util.SnowflakeIdWorker.getInstance().nextId())
                        .action(auditLog.action())
                        .resourceType(auditLog.resourceType())
                        .resourceId(resourceId)
                        .status(status)
                        .details(details)
                        .oldState(oldNode)
                        .newState(newNode)
                        .diffs(diffs)
                        .build();

                auditLogger.info(objectMapper.writeValueAsString(msg));
            } catch (Exception ignored) {
            } finally {
                AuditContextHolder.clear();
            }
        }
    }

    private List<String> generateDiff(JsonNode oldNode, JsonNode newNode) {
        List<String> diffs = new ArrayList<>();
        if (oldNode == null && newNode == null) {
            return diffs;
        }
        if (oldNode == null) {
            diffs.add("Created new object");
            return diffs;
        }
        if (newNode == null) {
            diffs.add("Deleted object");
            return diffs;
        }

        if (oldNode.isObject() && newNode.isObject()) {
            Iterator<String> newFields = newNode.fieldNames();
            while (newFields.hasNext()) {
                String field = newFields.next();
                JsonNode oldVal = oldNode.get(field);
                JsonNode newVal = newNode.get(field);

                if (oldVal == null || oldVal.isNull()) {
                    if (newVal != null && !newVal.isNull()) {
                        diffs.add(field + ": null -> " + newVal.asText());
                    }
                } else if (!oldVal.equals(newVal)) {
                    diffs.add(field + ": " + oldVal.asText() + " -> " + newVal.asText());
                }
            }

            // Check for fields that were deleted
            Iterator<String> oldFields = oldNode.fieldNames();
            while (oldFields.hasNext()) {
                String field = oldFields.next();
                if (!newNode.has(field) || newNode.get(field).isNull()) {
                    JsonNode oldVal = oldNode.get(field);
                    if (oldVal != null && !oldVal.isNull()) {
                        diffs.add(field + ": " + oldVal.asText() + " -> null");
                    }
                }
            }
        } else if (!oldNode.equals(newNode)) {
            diffs.add("value: " + oldNode.asText() + " -> " + newNode.asText());
        }

        return diffs;
    }
}
