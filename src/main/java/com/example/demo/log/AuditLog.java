package com.example.demo.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String action();
    String resourceType();
    String resourceIdSpEL() default "";
    String oldObjectSpEL() default "";
    String newObjectSpEL() default "";
}
