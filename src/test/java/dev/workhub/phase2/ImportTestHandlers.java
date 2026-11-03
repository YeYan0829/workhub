package dev.workhub.phase2;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Import(Phase2Support.Handlers.class)
@interface ImportTestHandlers {}
