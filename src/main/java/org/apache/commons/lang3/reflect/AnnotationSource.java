/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.lang3.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes where a looked-up {@link Annotation} was found and the type-hierarchy path
 * that was walked to reach it.
 *
 * <p>
 * Instances are returned by {@link MethodUtils#getAnnotationSources(java.lang.reflect.Method, Class, MethodUtils.AnnotationSearchOptions)}
 * and {@link MethodUtils#getAnnotationSources(Class, Class, MethodUtils.AnnotationSearchOptions)}.
 * They are the &quot;source view&quot; of an annotation: every candidate that is visible
 * through the hierarchy is reported as a separate source, including all equally valid
 * candidates behind an interface &quot;diamond&quot;. This deliberately differs from
 * {@link java.lang.reflect.AnnotatedElement#getAnnotation(Class)} style lookups, which
 * return a single value and silently choose one candidate.
 * </p>
 *
 * <p>
 * The {@linkplain #getPath() path} is ordered from the lookup starting element (the
 * concrete method or class passed to the lookup) up to the element that actually declares
 * the returned annotation. Two candidates reached through a diamond that share the same
 * declaring element are reported once; different declaring elements always produce
 * distinct candidates, even when the interface graph merges.
 * </p>
 *
 * @param <A> the annotation type.
 * @since 3.21.0
 */
public final class AnnotationSource<A extends Annotation> {

    private final A annotation;

    private final AnnotatedElement source;

    private final List<? extends AnnotatedElement> path;

    /**
     * Constructs a new {@link AnnotationSource}.
     *
     * @param annotation the annotation instance found.
     * @param source the element that declares the annotation.
     * @param path the elements walked from the lookup element to {@code source}, inclusive.
     */
    AnnotationSource(final A annotation, final AnnotatedElement source,
            final List<? extends AnnotatedElement> path) {
        this.annotation = Objects.requireNonNull(annotation, "annotation");
        this.source = Objects.requireNonNull(source, "source");
        this.path = Collections.unmodifiableList(Objects.requireNonNull(path, "path"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AnnotationSource)) {
            return false;
        }
        final AnnotationSource<?> other = (AnnotationSource<?>) obj;
        return annotation.equals(other.annotation) && source.equals(other.source) && path.equals(other.path);
    }

    /**
     * Gets the annotation instance that was found.
     *
     * @return the annotation, never {@code null}.
     */
    public A getAnnotation() {
        return annotation;
    }

    /**
     * Gets the element that actually declares {@link #getAnnotation()}.
     *
     * <p>
     * For a method lookup this is a {@link java.lang.reflect.Method}; for a type lookup it
     * is a {@link Class}.
     * </p>
     *
     * @return the declaring element, never {@code null}.
     */
    public AnnotatedElement getSource() {
        return source;
    }

    /**
     * Gets the inheritance path that was walked to reach {@link #getSource()}.
     *
     * <p>
     * The first entry is the lookup starting element; the last entry equals
     * {@link #getSource()}. The list is unmodifiable.
     * </p>
     *
     * @return the source path, never {@code null} or empty.
     */
    public List<? extends AnnotatedElement> getPath() {
        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(annotation, source, path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AnnotationSource[" + annotation.annotationType().getName() + " on " + source + ", depth=" + path.size() + "]";
    }
}
