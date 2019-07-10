package com.google.common.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link NonNull} is a type annotation that indicates that an expression is never {@code null}.
 *
 * <p>For fields of a class, the {@link NonNull} annotation indicates that this field is never
 * {@code null} <em>after the class has been fully initialized</em>. Class initialization is
 * controlled by the Freedom Before Commitment type system, see {@link
 * org.checkerframework.checker.initialization.InitializationChecker} for more details.
 *
 * <p>For static fields, the {@link NonNull} annotation indicates that this field is never {@code
 * null} <em>after the containing class is initialized</em>.
 *
 * <p>This annotation is rarely written in source code, because it is the default.
 *
 * <p>This annotation is associated with the {@link
 * org.checkerframework.checker.nullness.AbstractNullnessChecker}.
 *
 * @see Nullable
 * @see MonotonicNonNull
 * @see org.checkerframework.checker.nullness.AbstractNullnessChecker
 * @checker_framework.manual #nullness-checker Nullness Checker
 * @checker_framework.manual #bottom-type the bottom type
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
public @interface NonNull {}
