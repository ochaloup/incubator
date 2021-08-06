/**
 * Copyright The Narayana Authors
 *
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package io.narayana.lra.checker.failures;

import io.narayana.lra.checker.common.Tuple;
import jakarta.enterprise.inject.spi.AnnotatedMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ErrorDetailsPrinter {
    private ErrorDetailsPrinter() {
        // utility
    }

    public static Function<Class<?>, BiFunction<Class<? extends Annotation>, List<AnnotatedMethod<?>>, String>> MULTIPLE_ANNOTATIONS =
            annotatedLraClass -> (clazz, methods) ->
                    String.format(
                        "Multiple annotations '%s' in the class '%s' on methods %s.",
                        clazz.getName(), annotatedLraClass, toMethodNames(methods));

    private static List<String> toMethodNames(List<AnnotatedMethod<?>> annotatedMethods) {
        return annotatedMethods.stream().map(a -> a.getJavaMember().getName()).collect(Collectors.toList());
    }
}
