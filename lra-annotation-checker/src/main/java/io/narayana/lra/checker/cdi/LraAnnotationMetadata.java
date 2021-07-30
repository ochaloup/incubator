/*
 * Copyright The Narayana Authors
 *
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package io.narayana.lra.checker.cdi;

import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LraAnnotationMetadata<X> {
    static final List<Class<? extends Annotation>> LRA_METHOD_ANNOTATIONS = Arrays.asList(
            Compensate.class,
            Complete.class,
            AfterLRA.class,
            Forget.class,
            Status.class,
            Leave.class);

    // class with @LRA
    private final AnnotatedType<X> lraAnnotatedType;
    private final Class<X> lraAnnotatedCass;
    // class hierarchy of the class with @LRA
    private final List<Class<? super X>> classHierarchy;
    // LRA callback methods
    private final Map<Class<? extends Annotation>, List<AnnotatedMethod<? super X>>> lraMethods;

    LraAnnotationMetadata<X> loadMetadata(final AnnotatedType<X> lraAnnotatedClass) {
        return new LraAnnotationMetadata<>(lraAnnotatedClass);
    }

    private LraAnnotationMetadata(final AnnotatedType<X> lraAnnotatedType) {
        Objects.requireNonNull(lraAnnotatedType);
        this.lraAnnotatedType = lraAnnotatedType;
        this.lraAnnotatedCass = lraAnnotatedType.getJavaClass();
        this.classHierarchy = getClassHierarchy(lraAnnotatedCass);
        lraMethods = LRA_METHOD_ANNOTATIONS.stream()
                .collect(Collectors.toMap(Function.identity(), this::getMethodsForAnnotation));
    }


    private List<Class<? super X>> getClassHierarchy(Class<X> childClass) {
        List<Class<? super X>> classHierarchy = new ArrayList<>();
        if (childClass == null) return classHierarchy;
        Class<? super X> classToAdd = childClass;
        while (classToAdd != null) {
            classHierarchy.add(classToAdd);
            classToAdd = classToAdd.getSuperclass();
        }
        return classHierarchy;
    }

    private List<Method> getMethodHierarchy(final List<Class<? super X>> classHierarchy, final Class<? extends Annotation> annotationClass) {
        List<Method> list = new ArrayList<>();
        for (Class<?> clazz : classHierarchy) {
            for (Method m : clazz.getMethods()) {
                if (Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType() == annotationClass)) {
                    list.add(m);
                }
            }
        }
        return list;
    }

    private List<AnnotatedMethod<? super X>> getMethodsForAnnotation(Class<? extends Annotation> annotationClass) {
        return lraAnnotatedType.getMethods().stream()
                .filter(m -> m.isAnnotationPresent(annotationClass))
                .filter(m -> {
                    // based on the class hierarchy it find the most concrete LRA callback, i.e.,
                    // class A is a parent of class B, both declares @AfterLRA annotation, only method from B will be returned here
                    List<Method> methodHierarchy = getMethodHierarchy(classHierarchy, annotationClass);
                    return !methodHierarchy.isEmpty() && m.getJavaMember().getDeclaringClass() == methodHierarchy.get(0).getDeclaringClass();
                })
                .collect(Collectors.toList());
    }
}
