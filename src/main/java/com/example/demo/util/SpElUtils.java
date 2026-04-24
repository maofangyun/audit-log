package com.example.demo.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

/**
 * SpEL 表达式解析工具类
 *
 * <p>封装 Spring Expression Language (SpEL) 的上下文构建和表达式求值，
 * 提供统一的异常兜底策略，避免在切面中出现重复的 try-catch 样板代码。
 *
 * <p>工具类，禁止实例化。
 */
public final class SpElUtils {

    private static final Logger log = LoggerFactory.getLogger(SpElUtils.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private SpElUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 根据参数名数组和参数值数组构建 SpEL 上下文。
     *
     * @param paramNames 参数名数组（可为 null）
     * @param args       参数值数组
     * @return 已填充变量的 {@link StandardEvaluationContext}
     */
    public static StandardEvaluationContext buildContext(String[] paramNames, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null && args != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return context;
    }

    /**
     * 将 SpEL 表达式求值为字符串。
     * 表达式为空或求值失败时返回 {@code fallback}。
     *
     * @param expression 表达式字符串，如 {@code "#req.id"}
     * @param context    求值上下文
     * @param fallback   失败时的默认返回值
     * @return 求值结果字符串，或 {@code fallback}
     */
    public static String evalString(String expression, EvaluationContext context, String fallback) {
        if (!StringUtils.hasText(expression)) {
            return fallback;
        }
        try {
            Object val = PARSER.parseExpression(expression).getValue(context);
            return val != null ? String.valueOf(val) : fallback;
        } catch (Exception e) {
            log.debug("[SpElUtils] 表达式求值失败，使用 fallback: expr={}, reason={}", expression, e.getMessage());
            return fallback;
        }
    }

    /**
     * 将 SpEL 表达式求值为任意对象。
     * 表达式为空或求值失败时返回 {@code null}。
     *
     * @param expression 表达式字符串
     * @param context    求值上下文
     * @return 求值结果，或 {@code null}
     */
    public static Object eval(String expression, EvaluationContext context) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            return PARSER.parseExpression(expression).getValue(context);
        } catch (Exception e) {
            log.debug("[SpElUtils] 表达式求值失败: expr={}, reason={}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * 向已有上下文添加变量（链式调用场景，如方法执行后设置 result）。
     *
     * @param context 已有上下文
     * @param name    变量名
     * @param value   变量值
     * @return 传入的 context（便于链式调用）
     */
    public static StandardEvaluationContext setVariable(StandardEvaluationContext context,
                                                        String name, Object value) {
        context.setVariable(name, value);
        return context;
    }
}
