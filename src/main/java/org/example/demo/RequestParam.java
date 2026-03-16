package org.example.demo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface RequestParam {
    String NO_DEFAULT_VALUE = "__NO_DEFAULT_VALUE__";
    String value();
    String defaultValue() default NO_DEFAULT_VALUE;
}