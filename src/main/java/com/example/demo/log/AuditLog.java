package com.example.demo.log;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解
 *
 * <p>标注在需要记录审计日志的方法上，由 {@link com.example.demo.aspect.AuditLogAspect} 拦截处理。
 *
 * <p>使用示例：
 * <pre>{@code
 * @AuditLog(
 *     action = "UPDATE_USER",
 *     resourceType = "USER",
 *     resourceIdSpEL = "#req.id",
 *     newObjectSpEL  = "#result"
 * )
 * public UserDto updateUser(UserDto req) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 操作类型，如 CREATE_ORDER、UPDATE_USER、DELETE_FILE */
    String action();

    /** 资源类型，如 ORDER、USER、FILE */
    String resourceType();

    /** 资源 ID 的 SpEL 表达式，如 {@code "#req.id"} */
    String resourceIdSpEL() default "";

    /** 操作人 ID 的 SpEL 表达式（优先级低于 MDC 中的 userId）*/
    String userIdSpEL() default "";

    /** 修改前对象的 SpEL 表达式（优先级低于 AuditContextHolder）*/
    String oldObjectSpEL() default "";

    /** 修改后对象的 SpEL 表达式，可通过 {@code #result} 引用方法返回值 */
    String newObjectSpEL() default "";
}
