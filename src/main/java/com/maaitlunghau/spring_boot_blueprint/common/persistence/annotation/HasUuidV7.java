package com.maaitlunghau.spring_boot_blueprint.common.persistence.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;

import org.hibernate.annotations.IdGeneratorType;
import com.maaitlunghau.spring_boot_blueprint.common.persistence.generator.UuidV7Generator;

@Target({ FIELD, METHOD })
@Retention(RetentionPolicy.RUNTIME)
@IdGeneratorType(UuidV7Generator.class)
public @interface HasUuidV7 {

}
