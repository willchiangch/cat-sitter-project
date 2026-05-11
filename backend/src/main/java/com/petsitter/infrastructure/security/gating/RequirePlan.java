package com.petsitter.infrastructure.security.gating;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePlan {
    PlanTier value() default PlanTier.BASIC;
}
