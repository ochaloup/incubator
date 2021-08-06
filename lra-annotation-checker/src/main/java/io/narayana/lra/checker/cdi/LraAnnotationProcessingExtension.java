/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package io.narayana.lra.checker.cdi;

import io.narayana.lra.checker.common.Tuple;
import io.narayana.lra.checker.failures.ErrorCode;
import io.narayana.lra.checker.failures.ErrorDetailsPrinter;
import io.narayana.lra.checker.failures.FailureCatalog;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.WithAnnotations;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;
import org.jboss.logging.Logger;
import org.jboss.weld.util.collections.Sets;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.narayana.lra.checker.cdi.LraAnnotationMetadata.LRA_METHOD_ANNOTATIONS;

/**
 * <p>
 * This is the annotation processing class in plugin.
 * It's written as a CDI extension that gets all LRA annotations within the project
 * and checks whether they break the rules of LRA specification.
 * </p>
 * <p>
 * All failures are stored under {@link FailureCatalog}.
 * </p>
 */
public class LraAnnotationProcessingExtension implements Extension {
    private static final Logger log = Logger.getLogger(LraAnnotationProcessingExtension.class);

    <X> void processLraAnnotatedType(@Observes @WithAnnotations({LRA.class}) ProcessAnnotatedType<X> classAnnotatedWithLra) {
        log.debugf("Processing class:", classAnnotatedWithLra);

        // Let working only with instantiable classes - no abstract, no interface
        Class<X> classAnnotated = classAnnotatedWithLra.getAnnotatedType().getJavaClass();
        if (classAnnotated.isAnnotation() || classAnnotated.isEnum() || classAnnotated.isInterface() || Modifier.isAbstract(classAnnotated.getModifiers())) {
            log.debugf("Skipping class: %s as it's not standard instantiable class", classAnnotatedWithLra);
            return;
        }

        Supplier<Stream<AnnotatedMethod<? super X>>> methodsSupplier = () -> classAnnotatedWithLra.getAnnotatedType().getMethods().stream();
        String classAnnotatedWithLraName = classAnnotated.getName();

        // LRA class has to contain @Compensate or @AfterLRA
        if (methodsSupplier.get().noneMatch(m -> m.isAnnotationPresent(Compensate.class) || m.isAnnotationPresent(AfterLRA.class))) {
            FailureCatalog.INSTANCE.add(ErrorCode.MISSING_ANNOTATIONS_COMPENSATE_AFTER_LRA,
                    "Class: " + classAnnotatedWithLraName);
        }

        LraAnnotationMetadata<X> metadata = LraAnnotationMetadata.loadMetadata(classAnnotatedWithLra.getAnnotatedType());

        // Only one of these annotations is permitted in a class
        LRA_METHOD_ANNOTATIONS.stream()
                // @Status and @Leave may be used multiple times within one class (spec says nothing in particular on this)
                .filter(clazz -> clazz == clazz )
                .map(lraClass -> Tuple.of(lraClass, metadata.getDeclaredMethods(lraClass)))
                .filter(t -> t.getValue().size() > 1) // multiple methods for the annotation was found
                .forEach(t -> FailureCatalog.INSTANCE.add(ErrorCode.MULTIPLE_ANNOTATIONS_OF_THE_SAME_TYPE,
                        ErrorDetailsPrinter.MULTIPLE_ANNOTATIONS.apply(classAnnotated).apply(t.getKey(), t.getValue())));

        // Multiple different LRA annotations at the same method does not make sense
        Set<Set<Class<? extends Annotation>>> lraAnnotationsCombination = LRA_METHOD_ANNOTATIONS.stream()
                .flatMap(lraAnnotation -> LRA_METHOD_ANNOTATIONS.stream()
                        .flatMap(lraAnnotation2 -> Stream.of(Sets.newHashSet(lraAnnotation, lraAnnotation2))))
                .filter(s -> s.size() == 2)
                .collect(Collectors.toSet());
        methodsSupplier.get().filter(method -> lraAnnotationsCombination.stream()
                .anyMatch(annotationSet -> annotationSet.stream().allMatch(method::isAnnotationPresent)))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.MULTIPLE_ANNOTATIONS_OF_VARIOUS_TYPES,
                        String.format("Method '%s', class '%s', annotations '%s'.", method.getJavaMember().getName(),
                                method.getJavaMember().getDeclaringClass(), method.getAnnotations())));

        // -------------------------------------------------------------------------------------------
        // CDI style methods does not require @Path/@<method> but requires particular method signature
        // compensate
        final String signatureFormat = "public void/CompletionStage/ParticipantStatus %s(java.net.URI lraId, java.net.URI parentId)";
        List<AnnotatedMethod<? super X>> methodsWithCompensateNonJaxRS = methodsWithCompensate.stream()
                .filter(method -> !method.isAnnotationPresent(Path.class)) // it's not a REST method, checking on @Path(!?)
                .collect(Collectors.toList());
        methodsWithCompensate.removeAll(methodsWithCompensateNonJaxRS);
        methodsWithCompensateNonJaxRS.stream()
                // method signature for @Compensate: public void/CompleteStage compensate(URI lraId, URI parentId) { ...}
                .filter(LraAnnotationProcessingExtension.createNonJaxRsMethodSignatureChecker(URI.class, URI.class))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_METHOD_SIGNATURE_NON_JAXRS_RESOURCE,
                        getMethodSignatureErrorMsg(method, method.getJavaMember().getDeclaringClass(), Compensate.class,
                                String.format(signatureFormat, Compensate.class.getSimpleName().toLowerCase(Locale.ROOT)))));
        // complete
        List<AnnotatedMethod<? super X>> methodsWithCompleteNonJaxRS = methodsWithComplete.stream()
                .filter(method -> !method.isAnnotationPresent(Path.class)) // it's not a REST method(!?)
                .collect(Collectors.toList());
        methodsWithComplete.removeAll(methodsWithCompleteNonJaxRS);
        methodsWithCompleteNonJaxRS.stream()
                .filter(LraAnnotationProcessingExtension.createNonJaxRsMethodSignatureChecker(URI.class, URI.class))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_METHOD_SIGNATURE_NON_JAXRS_RESOURCE,
                        getMethodSignatureErrorMsg(method, method.getJavaMember().getDeclaringClass(), Complete.class,
                                String.format(signatureFormat, Complete.class.getSimpleName().toLowerCase(Locale.ROOT)))));
        // status
        List<AnnotatedMethod<? super X>> methodsWithStatusNonJaxRS = methodsWithStatus.stream()
                .filter(method -> !method.isAnnotationPresent(Path.class)) // it's not a REST method(!?)
                .collect(Collectors.toList());
        methodsWithStatus.removeAll(methodsWithStatusNonJaxRS);
        methodsWithStatusNonJaxRS.stream()
                .filter(LraAnnotationProcessingExtension.createNonJaxRsMethodSignatureChecker(URI.class, URI.class))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_METHOD_SIGNATURE_NON_JAXRS_RESOURCE,
                        getMethodSignatureErrorMsg(method, method.getJavaMember().getDeclaringClass(), Status.class,
                                String.format(signatureFormat, Status.class.getSimpleName().toLowerCase(Locale.ROOT)))));

        // afterlra
        List<AnnotatedMethod<? super X>> methodsWithAfterLRANonJaxRS = methodsWithAfterLRA.stream()
                .filter(method -> !method.isAnnotationPresent(Path.class)) // it's not a REST method(!?)
                .collect(Collectors.toList());
        methodsWithAfterLRANonJaxRS.forEach(methodsWithAfterLRA::remove);
        methodsWithAfterLRANonJaxRS.stream()
                .filter(LraAnnotationProcessingExtension.createNonJaxRsMethodSignatureChecker(URI.class, LRAStatus.class))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_METHOD_SIGNATURE_NON_JAXRS_RESOURCE,
                        getMethodSignatureErrorMsg(method, method.getJavaMember().getDeclaringClass(), AfterLRA.class,
                                String.format(signatureFormat, AfterLRA.class.getSimpleName().toLowerCase(Locale.ROOT)))));

        // forget
        List<AnnotatedMethod<? super X>> methodsWithForgetNonJaxRS = methodsWithForget.stream()
                .filter(method -> !method.isAnnotationPresent(Path.class)) // it's not a REST method(!?)
                .collect(Collectors.toList());
        methodsWithForget.removeAll(methodsWithForgetNonJaxRS);
        methodsWithForgetNonJaxRS.stream()
                .filter(LraAnnotationProcessingExtension.createNonJaxRsMethodSignatureChecker(URI.class, URI.class))
                .forEach(method -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_METHOD_SIGNATURE_NON_JAXRS_RESOURCE,
                        getMethodSignatureErrorMsg(method, method.getJavaMember().getDeclaringClass(), Forget.class,
                                String.format(signatureFormat, Forget.class.getSimpleName().toLowerCase(Locale.ROOT)))));

        // --------------------------------------------------------------------------------------------
        // REST style methods requires all necessary REST annotations
        if (methodsWithCompensate.size() > 0) { // only one @Compensate method permitted on class
            // @Compensate - requires @Path and @PUT
            final AnnotatedMethod<? super X> methodWithCompensate = methodsWithCompensate.get(0);
            Consumer<Class<? extends Annotation>> compensateMissingJaxRsAnnotation = missingJaxRsAnnotationFailure(methodWithCompensate, Compensate.class);
            boolean isCompensateContainsPathAnnotation = methodWithCompensate.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isCompensateContainsPathAnnotation) {
                compensateMissingJaxRsAnnotation.accept(Path.class);
            }
            boolean isCompensateContainsPutAnnotation = methodWithCompensate.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isCompensateContainsPutAnnotation) {
                compensateMissingJaxRsAnnotation.accept(PUT.class);
            }
            boolean isCompensateParametersContainsSuspended = methodWithCompensate.getParameters().stream().flatMap(p -> p.getAnnotations().stream())
                    .anyMatch(a -> a.annotationType().equals(Suspended.class));
            if (isCompensateParametersContainsSuspended) {
                if (methodsWithStatus.size() == 0 || methodsWithForget.size() == 0) {
                    FailureCatalog.INSTANCE.add(getMissingAnnotationsForAsyncHandling(methodWithCompensate, Compensate.class));
                }
            }
        }

        if (methodsWithAfterLRA.size() > 0) {
            // @AfterLRA - requires @Path and @PUT
            final AnnotatedMethod<? super X> methodWithAfterLRA = methodsWithAfterLRA.iterator().next();
            Consumer<Class<? extends Annotation>> afterLRAMissingJaxRsAnnotation = missingJaxRsAnnotationFailure(methodWithAfterLRA, AfterLRA.class);
            boolean isAfterLRAContainsPathAnnotation = methodWithAfterLRA.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isAfterLRAContainsPathAnnotation) {
                afterLRAMissingJaxRsAnnotation.accept(Path.class);
            }
            boolean isCompensateContainsPutAnnotation = methodWithAfterLRA.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isCompensateContainsPutAnnotation) {
                afterLRAMissingJaxRsAnnotation.accept(PUT.class);
            }
        }

        if (methodsWithComplete.size() > 0) {
            // @Complete - requires @Path and @PUT
            final AnnotatedMethod<? super X> methodWithComplete = methodsWithComplete.get(0);
            Consumer<Class<? extends Annotation>> completeMissingJaxRsAnnotation = missingJaxRsAnnotationFailure(methodWithComplete, Complete.class);
            boolean isCompleteContainsPathAnnotation = methodWithComplete.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isCompleteContainsPathAnnotation) {
                completeMissingJaxRsAnnotation.accept(Path.class);
            }
            boolean isCompleteContainsPutAnnotation = methodWithComplete.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isCompleteContainsPutAnnotation) {
                completeMissingJaxRsAnnotation.accept(PUT.class);
            }
            boolean isCompleteParametersContainsSuspended = methodWithComplete.getParameters().stream().flatMap(p -> p.getAnnotations().stream())
                    .anyMatch(a -> a.annotationType().equals(Suspended.class));
            if (isCompleteParametersContainsSuspended) {
                if (methodsWithStatus.size() == 0 || methodsWithForget.size() == 0) {
                    FailureCatalog.INSTANCE.add(getMissingAnnotationsForAsyncHandling(methodWithComplete, Complete.class));
                }
            }
        }

        if (methodsWithStatus.size() > 0) {
            // @Status - requires @Path and @GET
            final AnnotatedMethod<? super X> methodWithStatus = methodsWithStatus.get(0);
            Consumer<Class<? extends Annotation>> statusMissingJaxRsAnnotation = missingJaxRsAnnotationFailure(methodWithStatus, Status.class);
            boolean isStatusContainsPathAnnotation = methodWithStatus.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(Path.class));
            if (!isStatusContainsPathAnnotation) {
                statusMissingJaxRsAnnotation.accept(Path.class);
            }
            boolean isStatusContainsGetAnnotation = methodWithStatus.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(GET.class));
            if (!isStatusContainsGetAnnotation) {
                statusMissingJaxRsAnnotation.accept(GET.class);
            }
        }

        if (methodsWithLeave.size() > 0) {
            // @Leave - requires @PUT
            final AnnotatedMethod<? super X> methodWithLeave = methodsWithLeave.get(0);
            boolean isLeaveContainsPutAnnotation = methodWithLeave.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(PUT.class));
            if (!isLeaveContainsPutAnnotation) {
                missingJaxRsAnnotationFailure(methodWithLeave, Leave.class).accept(PUT.class);
            }
        }

        if (methodsWithForget.size() > 0) {
            // @Forget - requires @DELETE
            final AnnotatedMethod<? super X> methodWithForget = methodsWithForget.get(0);
            boolean isForgetContainsPutAnnotation = methodWithForget.getAnnotations().stream().anyMatch(a -> a.annotationType().equals(DELETE.class));
            if (!isForgetContainsPutAnnotation) {
                missingJaxRsAnnotationFailure(methodWithForget, Forget.class).accept(DELETE.class);
            }
        }
    }

    private static Consumer<Class<? extends Annotation>> missingJaxRsAnnotationFailure(AnnotatedMethod<?> method, Class<? extends Annotation> annotation) {
        return missingAnnotation -> FailureCatalog.INSTANCE.add(ErrorCode.WRONG_JAXRS_COMPLEMENTARY_ANNOTATION,
                String.format("Method '%s' of class '%s' annotated with '%s' misses complementary annotation %s.",
                        method.getJavaMember().getName(), method.getJavaMember().getDeclaringClass(),
                        annotation.getName(), missingAnnotation.getName()));

    }

    private static String getMissingAnnotationsForAsyncHandling(AnnotatedMethod<?> method, Class<?> completionAnnotation) {
        return String.format("Method '%s' of class '%s' annotated with '%s' is defined being asynchronous via @Suspend parameter annotation. " +
            "The LRA class has to contain @Status and @Forget annotations to activate such handling.",
                method.getJavaMember().getName(), method.getJavaMember().getDeclaringClass().getName(),
                completionAnnotation.getName());
    }

    private static String getMethodSignatureErrorMsg(AnnotatedMethod<?> method, Class<?> clazz, Class<?> lraTypeAnnotation, String correctSignature) {
        return String.format("Signature for annotation '%s' in the class '%s' on method '%s'. It should be '%s'",
                lraTypeAnnotation.getName(), clazz.getName(), method.getJavaMember().getName(), correctSignature);
    }

    private static Predicate<AnnotatedMethod<?>> createNonJaxRsMethodSignatureChecker(Class<?>... expectedParameterTypes) {
        return method -> {
            // when the method is not public
            if (!Modifier.isPublic(method.getJavaMember().getModifiers())) {
                return true;
            }
            Class<?>[] parameterTypes = method.getJavaMember().getParameterTypes();
            // some number of parameters are considered but there is no parameter provided in method declaration then fail
            if (expectedParameterTypes.length > 0 && method.getJavaMember().getParameterCount() == 0) {
                return true;
            }
            // if number of declared parameters is bigger than the expected number of paramters
            if (method.getJavaMember().getParameterCount() > expectedParameterTypes.length) {
                return true;
            }
            // the number of declared method parameters do not need to match but those provided have to be of same type
            for (int i = 0; i < method.getJavaMember().getParameterCount(); i++) {
                if(!expectedParameterTypes[i].isAssignableFrom(parameterTypes[i])) return true; // one of the parameter types does not match
            }
            // return type is is one of Void, CompletionStage, ParticipantStatus or Response
            if (!method.getJavaMember().getReturnType().equals(Void.TYPE)
                    && !method.getJavaMember().getReturnType().equals(CompletionStage.class)
                    && !method.getJavaMember().getReturnType().equals(ParticipantStatus.class)
                    && !method.getJavaMember().getReturnType().equals(Response.class)) {
                return true;
            }
            // all checks passed: the method is public with matching parameter types
            return false;
        };
    }
}
