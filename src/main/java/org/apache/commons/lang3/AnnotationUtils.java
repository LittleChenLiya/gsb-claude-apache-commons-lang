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
package org.apache.commons.lang3;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.builder.AbstractReflection;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.exception.UncheckedException;

/**
 * Helper methods for working with {@link Annotation} instances.
 *
 * <p>
 * This class contains various utility methods that make working with
 * annotations simpler.
 * </p>
 *
 * <p>
 * {@link Annotation} instances are always proxy objects; unfortunately
 * dynamic proxies cannot be depended upon to know how to implement certain
 * methods in the same manner as would be done by "natural" {@link Annotation}s.
 * The methods presented in this class can be used to avoid that possibility. It
 * is of course also possible for dynamic proxies to actually delegate their
 * e.g. {@link Annotation#equals(Object)}/{@link Annotation#hashCode()}/
 * {@link Annotation#toString()} implementations to {@link AnnotationUtils}.
 * </p>
 *
 * <p>
 * #ThreadSafe#
 * </p>
 *
 * @since 3.0
 */
public class AnnotationUtils {

    /**
     * A single candidate source of an annotation looked up along the method
     * override relationship.
     *
     * <p>
     * Each instance represents one method in the override closure of the
     * queried method on which the requested annotation is declared, together
     * with the inheritance path that leads from the queried method to that
     * declaring method. When several interfaces declare the same annotated
     * method (a "diamond"), every conflicting candidate is reported as its own
     * {@code AnnotationSource}; no arbitrary choice is made.
     * </p>
     *
     * @since 3.21.1
     */
    public static final class AnnotationSource {

        /** Path from the queried method (first) to the declaring method (last). */
        private final List<Method> path;

        /** The annotation as declared on the source method. */
        private final Annotation annotation;

        private AnnotationSource(final List<Method> path, final Annotation annotation) {
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.annotation = annotation;
        }

        /**
         * Gets the annotation found at the source of the path.
         *
         * @return the annotation, never {@code null}
         */
        public Annotation getAnnotation() {
            return annotation;
        }

        /**
         * Gets the queried method, i.e. the first element of the path.
         *
         * @return the queried method
         */
        public Method getMethod() {
            return path.get(0);
        }

        /**
         * Gets the inheritance path from the queried method to the declaring
         * method, following the override relationship. The first element is
         * the queried method, the last element is the method on which the
         * annotation is declared. Bridge methods never appear in the path.
         *
         * @return the unmodifiable path, never {@code null} or empty
         */
        public List<Method> getPath() {
            return path;
        }

        /**
         * Gets the method on which the annotation is declared, i.e. the last
         * element of the path.
         *
         * @return the declaring method
         */
        public Method getSource() {
            return path.get(path.size() - 1);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append(AnnotationUtils.toString(annotation)).append(" at ");
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    builder.append(" -> ");
                }
                builder.append(AnnotationUtils.toString(path.get(i)));
            }
            return builder.toString();
        }
    }

    /**
     * A style that prints annotations as recommended.
     */
    private static final ToStringStyle TO_STRING_STYLE = new ToStringStyle() {

        /** Serialization version */
        private static final long serialVersionUID = 1L;

        {
            setDefaultFullDetail(true);
            setArrayContentDetail(true);
            setUseClassName(true);
            setUseShortClassName(true);
            setUseIdentityHashCode(false);
            setContentStart("(");
            setContentEnd(")");
            setFieldSeparator(", ");
            setArrayStart("[");
            setArrayEnd("]");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void appendDetail(final StringBuffer buffer, final String fieldName, Object value) {
            if (value instanceof Annotation) {
                value = AnnotationUtils.toString((Annotation) value);
            }
            super.appendDetail(buffer, fieldName, value);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getShortClassName(final Class<?> cls) {
            // formatter:off
            return ClassUtils.getAllInterfaces(cls).stream().filter(Annotation.class::isAssignableFrom).findFirst()
                .map(iface -> "@" + iface.getName())
                .orElse(StringUtils.EMPTY);
            // formatter:on
        }

    };

    /**
     * Helper method for comparing two arrays of annotations.
     *
     * @param a1 The first array
     * @param a2 The second array
     * @return A flag whether these arrays are equal
     */
    private static boolean annotationArrayMemberEquals(final Annotation[] a1, final Annotation[] a2) {
        if (a1.length != a2.length) {
            return false;
        }
        for (int i = 0; i < a1.length; i++) {
            if (!equals(a1[i], a2[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Helper method for comparing two objects of an array type.
     *
     * @param componentType The component type of the array
     * @param o1 The first object
     * @param o2 The second object
     * @return A flag whether these objects are equal
     */
    private static boolean arrayMemberEquals(final Class<?> componentType, final Object o1, final Object o2) {
        if (componentType.isAnnotation()) {
            return annotationArrayMemberEquals((Annotation[]) o1, (Annotation[]) o2);
        }
        if (componentType.equals(Byte.TYPE)) {
            return Arrays.equals((byte[]) o1, (byte[]) o2);
        }
        if (componentType.equals(Short.TYPE)) {
            return Arrays.equals((short[]) o1, (short[]) o2);
        }
        if (componentType.equals(Integer.TYPE)) {
            return Arrays.equals((int[]) o1, (int[]) o2);
        }
        if (componentType.equals(Character.TYPE)) {
            return Arrays.equals((char[]) o1, (char[]) o2);
        }
        if (componentType.equals(Long.TYPE)) {
            return Arrays.equals((long[]) o1, (long[]) o2);
        }
        if (componentType.equals(Float.TYPE)) {
            return Arrays.equals((float[]) o1, (float[]) o2);
        }
        if (componentType.equals(Double.TYPE)) {
            return Arrays.equals((double[]) o1, (double[]) o2);
        }
        if (componentType.equals(Boolean.TYPE)) {
            return Arrays.equals((boolean[]) o1, (boolean[]) o2);
        }
        return Arrays.equals((Object[]) o1, (Object[]) o2);
    }

    /**
     * Helper method for generating a hash code for an array.
     *
     * @param componentType The component type of the array
     * @param o The array
     * @return A hash code for the specified array
     */
    private static int arrayMemberHash(final Class<?> componentType, final Object o) {
        if (componentType.equals(Byte.TYPE)) {
            return Arrays.hashCode((byte[]) o);
        }
        if (componentType.equals(Short.TYPE)) {
            return Arrays.hashCode((short[]) o);
        }
        if (componentType.equals(Integer.TYPE)) {
            return Arrays.hashCode((int[]) o);
        }
        if (componentType.equals(Character.TYPE)) {
            return Arrays.hashCode((char[]) o);
        }
        if (componentType.equals(Long.TYPE)) {
            return Arrays.hashCode((long[]) o);
        }
        if (componentType.equals(Float.TYPE)) {
            return Arrays.hashCode((float[]) o);
        }
        if (componentType.equals(Double.TYPE)) {
            return Arrays.hashCode((double[]) o);
        }
        if (componentType.equals(Boolean.TYPE)) {
            return Arrays.hashCode((boolean[]) o);
        }
        return Arrays.hashCode((Object[]) o);
    }

    /**
     * Checks if two annotations are equal using the criteria for equality
     * presented in the {@link Annotation#equals(Object)} API docs.
     *
     * @param a1 The first Annotation to compare, {@code null} returns
     * {@code false} unless both are {@code null}
     * @param a2 The second Annotation to compare, {@code null} returns
     * {@code false} unless both are {@code null}
     * @return {@code true} if the two annotations are {@code equal} or both
     * {@code null}
     */
    public static boolean equals(final Annotation a1, final Annotation a2) {
        if (a1 == a2) {
            return true;
        }
        if (a1 == null || a2 == null) {
            return false;
        }
        final Class<? extends Annotation> type1 = a1.annotationType();
        final Class<? extends Annotation> type2 = a2.annotationType();
        Validate.notNull(type1, "Annotation %s with null annotationType()", a1);
        Validate.notNull(type2, "Annotation %s with null annotationType()", a2);
        if (!type1.equals(type2)) {
            return false;
        }
        try {
            for (final Method m : type1.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0
                        && isValidAnnotationMemberType(m.getReturnType())) {
                    AbstractReflection.setAccessible(AbstractReflection.getForceAccessible(), m);
                    final Object v1 = m.invoke(a1);
                    final Object v2 = m.invoke(a2);
                    if (!memberEquals(m.getReturnType(), v1, v2)) {
                        return false;
                    }
                }
            }
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return true;
    }

    /**
     * Finds every candidate source of the given annotation for the given
     * method by following the method <em>override relationship</em> rather
     * than a plain same-name search.
     *
     * <p>
     * Starting at {@code method}, the lookup walks to the methods it
     * overrides or implements in superclasses and superinterfaces, and so on
     * transitively. A super method is only considered when name and
     * (erased) signature make it a genuine override or implementation
     * target; methods that merely share a name but declare different
     * parameters are ignored. Compiler-generated bridge methods are used to
     * recognize generic overrides but never appear as candidates or path
     * elements, so generic signatures produce no duplicate results.
     * </p>
     *
     * <p>
     * Every method in this override closure on which
     * {@code annotationType} is declared produces one
     * {@link AnnotationSource} holding the annotation and the inheritance
     * path leading to it. When a class implements two interfaces that both
     * declare the same annotated method (an interface "diamond"), all
     * conflicting candidates are returned instead of an arbitrary single
     * choice. When no method in the closure carries the annotation, an
     * empty list is returned.
     * </p>
     *
     * <p>
     * This method is an opt-in, additive view; it does not change the
     * behavior of any other method of this class, and callers that do not
     * use it observe no difference.
     * </p>
     *
     * @param method the method whose annotation sources are desired, not {@code null}
     * @param annotationType the annotation type to look for, not {@code null}
     * @return an unmodifiable list of candidate sources, in breadth-first
     * order from the queried method, never {@code null}
     * @since 3.21.1
     */
    public static List<AnnotationSource> getAnnotationSources(final Method method,
            final Class<? extends Annotation> annotationType) {
        Validate.notNull(method, "method");
        Validate.notNull(annotationType, "annotationType");
        final Method start = resolveBridge(method);
        final Map<Method, AnnotationSource> sources = new LinkedHashMap<>();
        final Map<Method, List<Method>> paths = new HashMap<>();
        final Set<Method> visited = new HashSet<>();
        final Deque<Method> queue = new ArrayDeque<>();
        visited.add(start);
        paths.put(start, Collections.singletonList(start));
        queue.add(start);
        while (!queue.isEmpty()) {
            final Method current = queue.poll();
            final Annotation annotation = current.getDeclaredAnnotation(annotationType);
            if (annotation != null && !sources.containsKey(current)) {
                sources.put(current, new AnnotationSource(paths.get(current), annotation));
            }
            for (final Method superMethod : getSuperMethods(current)) {
                if (visited.add(superMethod)) {
                    final List<Method> path = new ArrayList<>(paths.get(current));
                    path.add(superMethod);
                    paths.put(superMethod, path);
                    queue.add(superMethod);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(sources.values()));
    }

    /**
     * Generate a hash code for the given annotation using the algorithm
     * presented in the {@link Annotation#hashCode()} API docs.
     *
     * @param a The Annotation for a hash code calculation is desired, not
     * {@code null}
     * @return The calculated hash code
     * @throws RuntimeException Thrown if an {@link Exception} is encountered during annotation member access.
     * @throws IllegalStateException Thrown if an annotation method invocation returns {@code null}.
     */
    public static int hashCode(final Annotation a) {
        int result = 0;
        final Class<? extends Annotation> type = a.annotationType();
        for (final Method m : type.getDeclaredMethods()) {
            try {
                AbstractReflection.setAccessible(AbstractReflection.getForceAccessible(), m);
                final Object value = m.invoke(a);
                if (value == null) {
                    throw new IllegalStateException(String.format("Annotation method %s returned null", m));
                }
                result += hashMember(m.getName(), value);
            } catch (final ReflectiveOperationException ex) {
                throw new UncheckedException(ex);
            }
        }
        return result;
    }

    //besides modularity, this has the advantage of autoboxing primitives:
    /**
     * Helper method for generating a hash code for a member of an annotation.
     *
     * @param name The name of the member
     * @param value The value of the member
     * @return A hash code for this member
     */
    private static int hashMember(final String name, final Object value) {
        final int part1 = name.hashCode() * 127;
        if (ObjectUtils.isArray(value)) {
            return part1 ^ arrayMemberHash(value.getClass().getComponentType(), value);
        }
        if (value instanceof Annotation) {
            return part1 ^ hashCode((Annotation) value);
        }
        return part1 ^ value.hashCode();
    }

    /**
     * Tests whether the specified type is permitted as an annotation member.
     *
     * <p>
     * The Java language specification only permits certain types to be used
     * in annotations. These include {@link String}, {@link Class}, primitive
     * types, {@link Annotation}, {@link Enum}, and single-dimensional arrays of
     * these types.
     * </p>
     *
     * @param type The type to check, {@code null}
     * @return {@code true} if the type is a valid type to use in an annotation
     */
    public static boolean isValidAnnotationMemberType(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            type = type.getComponentType();
        }
        return type.isPrimitive() || type.isEnum() || type.isAnnotation()
                || String.class.equals(type) || Class.class.equals(type);
    }

    /**
     * Adds the method declared by {@code type} that {@code method} overrides
     * or implements, if any, to {@code result}. When both a bridge method and
     * its non-bridge counterpart match, the non-bridge method wins so that
     * bridge duplicates are never reported.
     *
     * @param type the super type to inspect
     * @param method the overriding/implementing method
     * @param result collects the matching super method
     * @return whether a matching method was declared by {@code type}
     */
    private static boolean addSuperMethod(final Class<?> type, final Method method, final List<Method> result) {
        Method match = null;
        for (final Method candidate : type.getDeclaredMethods()) {
            if (isOverridePair(candidate, method)
                    && (match == null || match.isBridge() && !candidate.isBridge())) {
                match = candidate;
            }
        }
        if (match == null) {
            return false;
        }
        result.add(match);
        return true;
    }

    /**
     * Collects the interface methods that {@code method} implements. When an
     * interface does not redeclare the method, its own superinterfaces are
     * searched transitively, so every branch of an interface diamond that
     * declares the method contributes a candidate.
     *
     * @param iface the interface to inspect
     * @param method the implementing method
     * @param result collects the matching interface methods
     * @param visited guards against revisiting interfaces in a diamond
     */
    private static void collectInterfaceMethods(final Class<?> iface, final Method method, final List<Method> result,
            final Set<Class<?>> visited) {
        if (!visited.add(iface)) {
            return;
        }
        if (!addSuperMethod(iface, method, result)) {
            for (final Class<?> parent : iface.getInterfaces()) {
                collectInterfaceMethods(parent, method, result, visited);
            }
        }
    }

    /**
     * Gets the methods that {@code method} directly overrides or implements:
     * the matching method declared by the superclass, if any, and the
     * matching methods declared by (possibly transitive) superinterfaces.
     *
     * @param method the method to inspect
     * @return the directly overridden/implemented methods, never {@code null}
     */
    private static List<Method> getSuperMethods(final Method method) {
        final int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            return Collections.emptyList();
        }
        final List<Method> result = new ArrayList<>();
        final Class<?> declaringClass = method.getDeclaringClass();
        final Class<?> superclass = declaringClass.getSuperclass();
        if (superclass != null) {
            addSuperMethod(superclass, method, result);
        }
        for (final Class<?> iface : declaringClass.getInterfaces()) {
            collectInterfaceMethods(iface, method, result, new HashSet<>());
        }
        return result;
    }

    /**
     * Tests whether {@code subMethod} overrides or implements
     * {@code superMethod}. Besides exact signature matches, a generic
     * override is recognized through the compiler-generated bridge method
     * that links the erased super signature to the concrete sub signature.
     *
     * @param superMethod the candidate overridden/implemented method
     * @param subMethod the candidate overriding/implementing method
     * @return whether the pair forms an override relationship
     */
    private static boolean isOverridePair(final Method superMethod, final Method subMethod) {
        if (!superMethod.getName().equals(subMethod.getName())
                || superMethod.getParameterCount() != subMethod.getParameterCount()) {
            return false;
        }
        final int modifiers = superMethod.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            return false;
        }
        if (Arrays.equals(superMethod.getParameterTypes(), subMethod.getParameterTypes())) {
            return true;
        }
        for (final Method bridge : subMethod.getDeclaringClass().getDeclaredMethods()) {
            if (bridge.isBridge() && bridge.getName().equals(subMethod.getName())
                    && Arrays.equals(bridge.getParameterTypes(), superMethod.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a compiler-generated bridge method to its non-bridge
     * counterpart declared by the same class, so that looking up sources for
     * a bridge method does not produce a duplicate view of the same logical
     * method. Non-bridge methods are returned unchanged.
     *
     * @param method the method to resolve
     * @return the non-bridge counterpart, or {@code method} itself
     */
    private static Method resolveBridge(final Method method) {
        if (!method.isBridge()) {
            return method;
        }
        for (final Method candidate : method.getDeclaringClass().getDeclaredMethods()) {
            if (!candidate.isBridge() && candidate.getName().equals(method.getName())
                    && candidate.getParameterCount() == method.getParameterCount()
                    && isAssignable(candidate.getParameterTypes(), method.getParameterTypes())
                    && method.getReturnType().isAssignableFrom(candidate.getReturnType())) {
                return candidate;
            }
        }
        return method;
    }

    /**
     * Tests whether each element of {@code to} is assignable from the
     * corresponding element of {@code from}.
     *
     * @param from the source types
     * @param to the target types
     * @return whether all pairs are assignable
     */
    private static boolean isAssignable(final Class<?>[] from, final Class<?>[] to) {
        if (from.length != to.length) {
            return false;
        }
        for (int i = 0; i < from.length; i++) {
            if (!to[i].isAssignableFrom(from[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generates a compact string representation of a method for
     * {@link AnnotationSource#toString()}.
     *
     * @param method the method to render
     * @return the string representation
     */
    private static String toString(final Method method) {
        final StringBuilder builder = new StringBuilder();
        builder.append(method.getDeclaringClass().getName()).append('.').append(method.getName()).append('(');
        final Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getSimpleName());
        }
        return builder.append(')').toString();
    }

    /**
     * Helper method for checking whether two objects of the given type are
     * equal. This method is used to compare the parameters of two annotation
     * instances.
     *
     * @param type The type of the objects to be compared
     * @param o1 The first object
     * @param o2 The second object
     * @return A flag whether these objects are equal
     */
    private static boolean memberEquals(final Class<?> type, final Object o1, final Object o2) {
        if (o1 == o2) {
            return true;
        }
        if (o1 == null || o2 == null) {
            return false;
        }
        if (type.isArray()) {
            return arrayMemberEquals(type.getComponentType(), o1, o2);
        }
        if (type.isAnnotation()) {
            return equals((Annotation) o1, (Annotation) o2);
        }
        return o1.equals(o2);
    }

    /**
     * Generate a string representation of an Annotation, as suggested by
     * {@link Annotation#toString()}.
     *
     * @param a The annotation of which a string representation is desired
     * @return The standard string representation of an annotation, not
     * {@code null}
     */
    public static String toString(final Annotation a) {
        final ToStringBuilder builder = new ToStringBuilder(a, TO_STRING_STYLE);
        for (final Method m : a.annotationType().getDeclaredMethods()) {
            if (m.getParameterTypes().length > 0) {
                continue; // what?
            }
            try {
                AbstractReflection.setAccessible(AbstractReflection.getForceAccessible(), m);
                builder.append(m.getName(), m.invoke(a));
            } catch (final ReflectiveOperationException ex) {
                throw new UncheckedException(ex);
            }
        }
        return builder.build();
    }

    /**
     * {@link AnnotationUtils} instances should NOT be constructed in
     * standard programming. Instead, the class should be used statically.
     *
     * <p>
     * This constructor is public to permit tools that require a JavaBean
     * instance to operate.
     * </p>
     *
     * @deprecated TODO Make private in 4.0.
     */
    @Deprecated
    public AnnotationUtils() {
        // empty
    }
}
