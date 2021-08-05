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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final Map<Class<? extends Annotation>, Optional<AnnotatedMethod<? super X>>> activeMethods;
    private final Map<Class<? extends Annotation>, List<AnnotatedMethod<? super X>>> declaredMethods;

    static <X> LraAnnotationMetadata loadMetadata(final AnnotatedType<X> lraAnnotatedClass) {
        return new LraAnnotationMetadata<>(lraAnnotatedClass);
    }

    private LraAnnotationMetadata(final AnnotatedType<X> lraAnnotatedType) {
        Objects.requireNonNull(lraAnnotatedType);
        this.lraAnnotatedType = lraAnnotatedType;
        this.lraAnnotatedCass = lraAnnotatedType.getJavaClass();
        this.classHierarchy = getClassHierarchy(lraAnnotatedCass);

        declaredMethods = LRA_METHOD_ANNOTATIONS.stream()
                .collect(Collectors.toMap(Function.identity(), this::getDeclaredMethodsForAnnotation));
        activeMethods = LRA_METHOD_ANNOTATIONS.stream()
                .collect(Collectors.toMap(Function.identity(), this::getAMethodForAnnotation));
    }

    /**
     * Returns all methods in the type hierarchy that are annotated with the annotation.
     */
    List<AnnotatedMethod<? super X>> getDeclaredMethods(final Class<? extends Annotation> annotationClass) {
        return declaredMethods.get(annotationClass);
    }

    /**
     * Class hierarchy is placed into list. The lowest index comes with the most childish class,
     * the biggest index contains the Object class as the parent of all classes in java.
     */
    private List<Class<? super X>> getClassHierarchy(final Class<X> childClass) {
        List<Class<? super X>> classHierarchy = new ArrayList<>();
        if (childClass == null) return classHierarchy;
        Class<? super X> classToAdd = childClass;
        while (classToAdd != null) {
            classHierarchy.add(classToAdd);
            classToAdd = classToAdd.getSuperclass();
        }
        return classHierarchy;
    }

    /**
     * Creates a method hierarchy annotated with particular annotation.
     * Based on the list created by {@link #getClassHierarchy(Class)} it returns a list of methods
     * which are annotated in this hierarchy by the specified annotation.
     *
     * E.g. let's have a class hierarchy A -> B -> C. The class C (the most concrete one) contains a method {@link Compensate},
     * class B contains no such method, class A contains again method annotated with @{@link Compensate}.
     * Then this method returns a list with method from C at index {@code 0} and method from A at index {@code 1}.
     */
    private Collection<Method> getMethodHierarchy(final List<Class<? super X>> classHierarchy, final Class<? extends Annotation> annotationClass) {
        Collection<Method> list = new ArrayList<>();
        for (Class<?> clazz : classHierarchy) {
            for (Method m : clazz.getMethods()) {
                if (Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType() == annotationClass)) {
                    list.add(m);
                }
            }
        }
        return list;
    }

    /**
     * Based on the class hierarchy it finds the most concrete LRA callback, i.e.,
     * class A is a parent of class B, both declares @AfterLRA annotation, only method from B will be returned here.
     * When the class hierarchy does *not* define a method for the annotation then empty {@link Optional} is returned.
     */
    private Optional<AnnotatedMethod<? super X>> getAMethodForAnnotation(final Class<? extends Annotation> annotationClass) {
        Optional<Method> mostConcreteAnnotatedMethod = getMethodHierarchy(classHierarchy, annotationClass).stream().findFirst();
        return getDeclaredMethodsForAnnotationStream(annotationClass)
                .filter(m -> {
                    return !mostConcreteAnnotatedMethod.isPresent() && m.getJavaMember().getDeclaringClass() == mostConcreteAnnotatedMethod.get().getDeclaringClass();
                })
                .findFirst();
    }

    /**
     * Returns all active annotated methods for the annotated type from whole type hierarchy as a stream.
     */
    private Stream<AnnotatedMethod<? super X>> getDeclaredMethodsForAnnotationStream(final Class<? extends Annotation> annotationClass) {
        return lraAnnotatedType.getMethods().stream()
                .filter(m -> m.isAnnotationPresent(annotationClass));
    }

    private List<AnnotatedMethod<? super X>> getDeclaredMethodsForAnnotation(final Class<? extends Annotation> annotationClass) {
        return getDeclaredMethodsForAnnotationStream(annotationClass)
                .collect(Collectors.toList());
    }
}
