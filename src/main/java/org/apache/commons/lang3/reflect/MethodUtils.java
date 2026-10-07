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
import java.lang.reflect.Array;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.ClassUtils.Interfaces;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.stream.LangCollectors;
import org.apache.commons.lang3.stream.Streams;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * Utility reflection methods focused on {@link Method}s, originally from Commons BeanUtils.
 * Differences from the BeanUtils version may be noted, especially where similar functionality
 * already existed within Lang.
 *
 * <h2>Known Limitations</h2>
 * <h3>Accessing Public Methods In A Default Access Superclass</h3>
 * <p>
 * There is an issue when invoking {@code public} methods contained in a default access superclass on JREs prior to 1.4.
 * Reflection locates these methods fine and correctly assigns them as {@code public}.
 * However, an {@link IllegalAccessException} is thrown if the method is invoked.
 * </p>
 *
 * <p>
 * {@link MethodUtils} contains a workaround for this situation.
 * It will attempt to call {@link java.lang.reflect.AccessibleObject#setAccessible(boolean)} on this method.
 * If this call succeeds, then the method can be invoked as normal.
 * This call will only succeed when the application has sufficient security privileges.
 * If this call fails then the method may fail.
 * </p>
 *
 * @since 2.5
 */
public class MethodUtils {

    private static final Comparator<Method> METHOD_BY_SIGNATURE = Comparator.comparing(Method::toString);

    /**
     * Computes the aggregate number of inheritance hops between assignable argument class types.  Returns -1
     * if the arguments aren't assignable.  Fills a specific purpose for getMatchingMethod and is not generalized.
     *
     * @param fromClassArray The Class array to calculate the distance from.
     * @param toClassArray The Class array to calculate the distance to.
     * @return The aggregate number of inheritance hops between assignable argument class types.
     */
    private static int distance(final Class<?>[] fromClassArray, final Class<?>[] toClassArray) {
        int answer = 0;
        if (!ClassUtils.isAssignable(fromClassArray, toClassArray, true)) {
            return -1;
        }
        for (int offset = 0; offset < fromClassArray.length; offset++) {
            // Note InheritanceUtils.distance() uses different scoring system.
            final Class<?> aClass = fromClassArray[offset];
            final Class<?> toClass = toClassArray[offset];
            if (aClass == null || aClass.equals(toClass)) {
                continue;
            }
            if (ClassUtils.isAssignable(aClass, toClass, true) && !ClassUtils.isAssignable(aClass, toClass, false)) {
                // Autoboxing/unboxing conversion. When a primitive is boxed, rank the exact
                // wrapper ahead of any of its supertypes so the most specific overload wins.
                answer++;
                if (aClass.isPrimitive() && !ClassUtils.primitiveToWrapper(aClass).equals(toClass)) {
                    answer += 2;
                }
            } else {
                answer += 2;
            }
        }
        return answer;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) that implements the specified Method. If no such method can be found, return
     * {@code null}.
     *
     * @param cls The implementing class, may be null.
     * @param method The method that we wish to call, may be null.
     * @return The accessible method or null.
     * @since 3.19.0
     */
    public static Method getAccessibleMethod(final Class<?> cls, final Method method) {
        if (!MemberUtils.isPublic(method)) {
            return null;
        }
        // If the declaring class is public, we are done
        if (ClassUtils.isPublic(cls)) {
            return method;
        }
        final String methodName = method.getName();
        final Class<?>[] parameterTypes = method.getParameterTypes();
        // Check the implemented interfaces and subinterfaces
        final Method method2 = getAccessibleMethodFromInterfaceNest(cls, methodName, parameterTypes);
        // Check the superclass chain
        return method2 != null ? method2 : getAccessibleMethodFromSuperclass(cls, methodName, parameterTypes);
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) with given name and parameters. If no such method can be found, return
     * {@code null}. This is just a convenience wrapper for {@link #getAccessibleMethod(Method)}.
     *
     * @param cls            get method from this class.
     * @param methodName     get method with this name.
     * @param parameterTypes with these parameters types.
     * @return The accessible method.
     */
    public static Method getAccessibleMethod(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        return getAccessibleMethod(getMethodObject(cls, methodName, parameterTypes));
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) that implements the specified Method. If no such method can be found, return
     * {@code null}.
     *
     * @param method The method that we wish to call, may be null.
     * @return The accessible method
     */
    public static Method getAccessibleMethod(final Method method) {
        return method != null ? getAccessibleMethod(method.getDeclaringClass(), method) : null;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via
     * reflection) that implements the specified method, by scanning through
     * all implemented interfaces and subinterfaces. If no such method
     * can be found, return {@code null}.
     *
     * <p>
     * There isn't any good reason why this method must be {@code private}.
     * It is because there doesn't seem any reason why other classes should
     * call this rather than the higher level methods.
     * </p>
     *
     * @param cls Parent class for the interfaces to be checked.
     * @param methodName Method name of the method we wish to call.
     * @param parameterTypes The parameter type signatures.
     * @return The accessible method or {@code null} if not found.
     */
    private static Method getAccessibleMethodFromInterfaceNest(Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        // Search up the superclass chain
        for (; cls != null; cls = cls.getSuperclass()) {
            // Check the implemented interfaces of the parent class
            final Class<?>[] interfaces = cls.getInterfaces();
            for (final Class<?> anInterface : interfaces) {
                // Is this interface public?
                if (!ClassUtils.isPublic(anInterface)) {
                    continue;
                }
                // Does the method exist on this interface? A static or private one is not inherited.
                try {
                    final Method declared = anInterface.getDeclaredMethod(methodName, parameterTypes);
                    if (MemberUtils.isPublic(declared) && !MemberUtils.isStatic(declared)) {
                        return declared;
                    }
                } catch (final NoSuchMethodException ignored) {
                    /*
                     * Swallow, if no method is found after the loop then this method returns null.
                     */
                }
                // Recursively check our parent interfaces
                final Method method = getAccessibleMethodFromInterfaceNest(anInterface, methodName, parameterTypes);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via
     * reflection) by scanning through the superclasses. If no such method
     * can be found, return {@code null}.
     *
     * @param cls Class to be checked.
     * @param methodName Method name of the method we wish to call.
     * @param parameterTypes The parameter type signatures.
     * @return The accessible method or {@code null} if not found.
     */
    private static Method getAccessibleMethodFromSuperclass(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        Class<?> parentClass = cls.getSuperclass();
        while (parentClass != null) {
            if (ClassUtils.isPublic(parentClass)) {
                return getMethodObject(parentClass, methodName, parameterTypes);
            }
            parentClass = parentClass.getSuperclass();
        }
        return null;
    }

    /**
     * Gets a combination of {@link ClassUtils#getAllSuperclasses(Class)} and {@link ClassUtils#getAllInterfaces(Class)}, one from superclasses, one from
     * interfaces, and so on in a breadth first way.
     *
     * @param cls The class to look up, may be {@code null}.
     * @return The combined {@link List} of superclasses and interfaces in order going up from this one {@code null} if null input.
     */
    private static List<Class<?>> getAllSuperclassesAndInterfaces(final Class<?> cls) {
        if (cls == null) {
            return null;
        }
        final List<Class<?>> allSuperClassesAndInterfaces = new ArrayList<>();
        final List<Class<?>> allSuperclasses = ClassUtils.getAllSuperclasses(cls);
        int superClassIndex = 0;
        final List<Class<?>> allInterfaces = ClassUtils.getAllInterfaces(cls);
        int interfaceIndex = 0;
        while (interfaceIndex < allInterfaces.size() || superClassIndex < allSuperclasses.size()) {
            final Class<?> acls;
            if (interfaceIndex >= allInterfaces.size() || superClassIndex < allSuperclasses.size() && superClassIndex < interfaceIndex) {
                acls = allSuperclasses.get(superClassIndex++);
            } else {
                acls = allInterfaces.get(interfaceIndex++);
            }
            allSuperClassesAndInterfaces.add(acls);
        }
        return allSuperClassesAndInterfaces;
    }

    /**
     * Gets the annotation object with the given annotation type that is present on the given method or optionally on any equivalent method in super classes and
     * interfaces. Returns null if the annotation type was not present.
     *
     * <p>
     * Stops searching for an annotation once the first annotation of the specified type has been found. Additional annotations of the specified type will be
     * silently ignored.
     * </p>
     *
     * @param <A>           the annotation type.
     * @param method        The {@link Method} to query, may be null.
     * @param annotationCls The {@link Annotation} to check if is present on the method.
     * @param searchSupers  determines if a lookup in the entire inheritance hierarchy of the given class is performed if the annotation was not directly
     *                      present.
     * @param ignoreAccess  determines if underlying method has to be accessible.
     * @return The first matching annotation, or {@code null} if not found.
     * @throws NullPointerException Thrown if either the method or annotation class is {@code null}.
     * @throws SecurityException    Thrown if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     * @since 3.6
     */
    public static <A extends Annotation> A getAnnotation(final Method method, final Class<A> annotationCls, final boolean searchSupers,
            final boolean ignoreAccess) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(annotationCls, "annotationCls");
        if (!ignoreAccess && !MemberUtils.isAccessible(method)) {
            return null;
        }
        A annotation = method.getAnnotation(annotationCls);
        if (annotation == null && searchSupers) {
            final Class<?> mcls = method.getDeclaringClass();
            final List<Class<?>> classes = getAllSuperclassesAndInterfaces(mcls);
            for (final Class<?> acls : classes) {
                final Method equivalentMethod = findOverrideMethod(method, acls);
                if (equivalentMethod != null && (ignoreAccess || MemberUtils.isAccessible(equivalentMethod))) {
                    annotation = equivalentMethod.getAnnotation(annotationCls);
                    if (annotation != null) {
                        break;
                    }
                }
            }
        }
        return annotation;
    }

    /**
     * Finds the method in the given class that {@code method} overrides or implements.
     *
     * <p>
     * First, an exact parameter-type match ({@link Class#getDeclaredMethod(String, Class[])}) is attempted to find a true override. This avoids matching
     * unrelated overloads that are merely assignable-compatible (e.g. {@code process(Integer)} vs {@code process(Number)}).
     * </p>
     * <p>
     * If no exact match exists, the declared methods are scanned for the generic-bridge scenario: the declaring class may use a type variable whose erased
     * form is {@link Object} (or another bound), so the parent method's erased parameter types differ from the child's concrete types.
     * </p>
     * <p>
     * Finally, if {@code candidateClass} is an interface that merely inherits a method from a super-interface without re-declaring it, the super-interface
     * graph is walked the same way so an interface diamond can be traversed through its intermediate nodes.
     * </p>
     * <p>
     * Compiler-generated {@link Method#isBridge() bridge} methods are never returned; a non-bridge method declared in the same class with the same
     * signature is preferred instead.
     * </p>
     *
     * @param method the method whose override to find.
     * @param candidateClass the class to search.
     * @return the overridden method, or {@code null} if no true override exists in {@code candidateClass} or its super-interfaces.
     */
    private static Method findOverrideMethod(final Method method, final Class<?> candidateClass) {
        final Method direct = findDeclaredOverrideMethod(method, candidateClass);
        if (direct != null) {
            return direct;
        }
        if (!candidateClass.isInterface()) {
            return null;
        }
        return findInheritedOverride(method, candidateClass);
    }

    /**
     * Resolves one overriding member per direct supertype of {@code method}'s declaring class.
     *
     * <p>
     * A direct supertype that re-declares the override contributes its own member; a direct supertype that only inherits the method is a pass-through edge
     * that resolves to the first member re-declared in its own super-interface graph. Compiler-generated bridges are never returned. The result may contain
     * the same {@link Method} more than once when the supertype graph merges; callers de-duplicate as needed.
     * </p>
     *
     * @param method the method whose overrides to resolve.
     * @return the resolved overridden members, in superclass-then-interface order; never {@code null}.
     */
    private static List<Method> findOverriddenMembers(final Method method) {
        final List<Method> result = new ArrayList<>();
        final Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass.getSuperclass() != null) {
            final Method overridden = findOverrideMethod(method, declaringClass.getSuperclass());
            if (overridden != null) {
                result.add(overridden);
            }
        }
        for (final Class<?> superInterface : declaringClass.getInterfaces()) {
            final Method overridden = findOverrideMethod(method, superInterface);
            if (overridden != null) {
                result.add(overridden);
            }
        }
        return result;
    }

    /**
     * Resolves a method override through one direct supertype, walking inherited (not re-declared) interface members when the supertype is an interface.
     *
     * @param method the method whose override to find.
     * @param firstType the direct supertype to start from.
     * @return the overridden member, or {@code null} if none of the nodes re-declares the method.
     */
    private static Method findInheritedOverride(final Method method, final Class<?> firstType) {
        final Method direct = findDeclaredOverrideMethod(method, firstType);
        if (direct != null) {
            return direct;
        }
        if (!firstType.isInterface()) {
            return null;
        }
        final Set<Class<?>> visited = new LinkedHashSet<>();
        final Deque<Class<?>> queue = new ArrayDeque<>();
        visited.add(firstType);
        queue.add(firstType);
        while (!queue.isEmpty()) {
            final Class<?> current = queue.removeFirst();
            for (final Class<?> superInterface : current.getInterfaces()) {
                if (!visited.add(superInterface)) {
                    continue;
                }
                final Method found = findDeclaredOverrideMethod(method, superInterface);
                if (found != null) {
                    return found;
                }
                queue.addLast(superInterface);
            }
        }
        return null;
    }

    /**
     * Finds a directly declared method that the given method overrides, without walking inherited interface members.
     *
     * @param method the method whose override to find.
     * @param candidateClass the class to search.
     * @return the overridden method, or {@code null} if no directly declared true override exists.
     */
    private static Method findDeclaredOverrideMethod(final Method method, final Class<?> candidateClass) {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        // First, attempt an exact parameter-type match (getDeclaredMethod) to
        // find a true override. This avoids matching unrelated overloads that
        // are merely assignable-compatible (e.g. process(Integer) vs
        // process(Number)).
        Method equivalentMethod = null;
        try {
            equivalentMethod = candidateClass.getDeclaredMethod(methodName, paramTypes);
        } catch (final NoSuchMethodException ignored) {
            // No exact match; check for generic-bridge scenario: the declaring
            // class may use a type variable whose erased form is Object (or
            // another bound). In that case the parent method's erased
            // parameter types differ from the child's concrete types, so we
            // scan declared methods for a same-name method whose *erased*
            // parameter count matches and whose erased types are assignable
            // from our concrete types.
            for (final Method candidate : candidateClass.getDeclaredMethods()) {
                if (candidate.isBridge() || !candidate.getName().equals(methodName)) {
                    continue;
                }
                final Class<?>[] candidateParams = candidate.getParameterTypes();
                if (candidateParams.length != paramTypes.length) {
                    continue;
                }
                // Require that every concrete param type is assignable to the
                // candidate's (erased) param type AND that the candidate is
                // generic (has at least one TypeVariable in its generic
                // parameter types). This prevents matching plain overloads.
                boolean genericMatch = false;
                boolean paramsMatch = true;
                final java.lang.reflect.Type[] genericParams = candidate.getGenericParameterTypes();
                for (int i = 0; i < candidateParams.length; i++) {
                    if (genericParams[i] instanceof java.lang.reflect.TypeVariable) {
                        genericMatch = true;
                    }
                    if (!ClassUtils.isAssignable(paramTypes[i], candidateParams[i], true)) {
                        paramsMatch = false;
                        break;
                    }
                }
                if (paramsMatch && genericMatch) {
                    equivalentMethod = candidate;
                    break;
                }
            }
        }
        // Never report a compiler-generated bridge; use the non-bridge method in the same class when available.
        if (equivalentMethod != null && equivalentMethod.isBridge()) {
            equivalentMethod = unbridge(equivalentMethod);
        }
        return equivalentMethod;
    }

    /**
     * Resolves a compiler-generated bridge method to the user method it delegates to within the same declaring class.
     *
     * @param bridge the potential bridge method.
     * @return the non-bridge method with the same name and parameter types declared by the same class, or the original method if none exists.
     */
    private static Method unbridge(final Method bridge) {
        if (!bridge.isBridge()) {
            return bridge;
        }
        for (final Method declared : bridge.getDeclaringClass().getDeclaredMethods()) {
            if (!declared.isBridge() && declared.getName().equals(bridge.getName())
                    && Arrays.equals(declared.getParameterTypes(), bridge.getParameterTypes())) {
                return declared;
            }
        }
        return bridge;
    }

    /**
     * Gets every visible source of the given annotation on the given method as a source view.
     *
     * <p>
     * Unlike {@link #getAnnotation(Method, Class, boolean, boolean)}, this method does not stop at the first hit and never silently chooses one candidate:
     * </p>
     * <ul>
     * <li>The override graph is walked along <em>true override relationships</em> (matching name and parameter signature), not merely methods with the same
     * name. An unrelated overload in a supertype is never reported as a source.</li>
     * <li>Compiler-generated {@link Method#isBridge() bridge} methods are skipped, so a generic override is reported once instead of once per bridge.</li>
     * <li>When an interface diamond places the annotation on several independently inherited members, every candidate is returned in subclass-to-supertype
     * order instead of an arbitrary single value.</li>
     * </ul>
     *
     * <p>
     * Each returned {@link AnnotationSource} exposes the annotation, its declaring element and the full inheritance path walked to reach it.
     * </p>
     *
     * @param <A> the annotation type.
     * @param method the method to query, must not be {@code null}.
     * @param annotationCls the annotation type to look up, must not be {@code null}.
     * @param options search options; {@code null} uses {@link AnnotationSearchOptions#DIRECT}.
     * @return the annotation sources in subclass-to-supertype order; an empty list when the annotation is absent.
     * @throws NullPointerException Thrown if the method or annotation class is {@code null}.
     * @since 3.21.0
     */
    public static <A extends Annotation> List<AnnotationSource<A>> getAnnotationSources(final Method method,
            final Class<A> annotationCls, final AnnotationSearchOptions options) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(annotationCls, "annotationCls");
        final AnnotationSearchOptions effective = options == null ? AnnotationSearchOptions.DIRECT : options;
        final Method root = unbridge(method);
        // Breadth-first walk of the override graph on (member, path-to-member) states. Each override edge is followed at
        // most once, so a member reached through several branches (interface diamond) is reported once while still
        // fanning out to its own overrides; members reached through distinct branches of a diamond are all returned in
        // subclass-to-supertype order. Pass-through interfaces that merely inherit the method do not add a path element;
        // only re-declaring members do.
        final Set<Method> reported = new LinkedHashSet<>();
        final Set<ImmutablePair<Method, Method>> followedEdges = new LinkedHashSet<>();
        final Deque<List<Method>> queue = new ArrayDeque<>();
        queue.addLast(new ArrayList<>(Collections.singletonList(root)));
        final List<AnnotationSource<A>> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            final List<Method> currentPath = queue.removeFirst();
            final Method current = currentPath.get(currentPath.size() - 1);
            if (reported.add(current)
                    && (effective.isIgnoreAccess() || MemberUtils.isAccessible(current))) {
                final A annotation = current.getDeclaredAnnotation(annotationCls);
                if (annotation != null) {
                    result.add(new AnnotationSource<>(annotation, current, currentPath));
                }
            }
            if (!effective.isSearchSupers()) {
                continue;
            }
            for (final Method next : findOverriddenMembers(current)) {
                // De-duplicate on the override edge (member pair), not the target member, so a diamond that merges into
                // one member keeps that member's outgoing edges alive on every branch.
                final ImmutablePair<Method, Method> edge = ImmutablePair.of(current, next);
                if (!followedEdges.add(edge)) {
                    continue;
                }
                final List<Method> nextPath = new ArrayList<>(currentPath.size() + 1);
                nextPath.addAll(currentPath);
                nextPath.add(next);
                queue.addLast(nextPath);
            }
        }
        return result;
    }

    /**
     * Gets every visible source of the given annotation on the given class or interface as a source view.
     *
     * <p>
     * Superclasses are followed before directly implemented interfaces, which are followed before their own super-interfaces; this is the same ordering
     * used by {@link #getAnnotation(Method, Class, boolean, boolean)}. An interface diamond is traversed breadth-first, so a member reached through several
     * paths is reported once, while independently annotated members of the diamond are all returned in type-to-supertype order.
     * </p>
     *
     * @param <A> the annotation type.
     * @param type the class or interface to query, must not be {@code null}.
     * @param annotationCls the annotation type to look up, must not be {@code null}.
     * @param options search options; {@code null} uses {@link AnnotationSearchOptions#DIRECT}.
     * @return the annotation sources in type-to-supertype order; an empty list when the annotation is absent.
     * @throws NullPointerException Thrown if the type or annotation class is {@code null}.
     * @since 3.21.0
     */
    public static <A extends Annotation> List<AnnotationSource<A>> getAnnotationSources(final Class<?> type,
            final Class<A> annotationCls, final AnnotationSearchOptions options) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(annotationCls, "annotationCls");
        final AnnotationSearchOptions effective = options == null ? AnnotationSearchOptions.DIRECT : options;
        // Breadth-first walk of the type graph. The key set is also the visited set, so a diamond merge is de-duplicated.
        final Map<Class<?>, List<Class<?>>> visited = new LinkedHashMap<>();
        final Deque<Class<?>> queue = new ArrayDeque<>();
        visited.put(type, Collections.singletonList(type));
        queue.add(type);
        final List<AnnotationSource<A>> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            final Class<?> current = queue.removeFirst();
            final A annotation = current.getDeclaredAnnotation(annotationCls);
            if (annotation != null) {
                result.add(new AnnotationSource<>(annotation, current, visited.get(current)));
            }
            if (!effective.isSearchSupers()) {
                continue;
            }
            final List<Class<?>> currentPath = visited.get(current);
            for (final Class<?> nextType : supertypes(current)) {
                if (nextType == null || visited.containsKey(nextType)) {
                    continue;
                }
                final List<Class<?>> nextPath = new ArrayList<>(currentPath.size() + 1);
                nextPath.addAll(currentPath);
                nextPath.add(nextType);
                visited.put(nextType, nextPath);
                queue.addLast(nextType);
            }
        }
        return result;
    }

    /**
     * Gets the annotation when exactly one source is visible on the given method.
     *
     * @param <A> the annotation type.
     * @param method the method to query, must not be {@code null}.
     * @param annotationCls the annotation type to look up, must not be {@code null}.
     * @param options search options; {@code null} uses {@link AnnotationSearchOptions#DIRECT}.
     * @return an optional holding the unique annotation; empty when the annotation is absent or several candidates compete.
     * @throws NullPointerException Thrown if the method or annotation class is {@code null}.
     * @since 3.21.0
     */
    public static <A extends Annotation> Optional<A> getUniqueAnnotation(final Method method, final Class<A> annotationCls,
            final AnnotationSearchOptions options) {
        final List<AnnotationSource<A>> sources = getAnnotationSources(method, annotationCls, options);
        return sources.size() == 1 ? Optional.of(sources.get(0).getAnnotation()) : Optional.empty();
    }

    /**
     * Gets the annotation when exactly one source is visible on the given class or interface.
     *
     * @param <A> the annotation type.
     * @param type the class or interface to query, must not be {@code null}.
     * @param annotationCls the annotation type to look up, must not be {@code null}.
     * @param options search options; {@code null} uses {@link AnnotationSearchOptions#DIRECT}.
     * @return an optional holding the unique annotation; empty when the annotation is absent or several candidates compete.
     * @throws NullPointerException Thrown if the type or annotation class is {@code null}.
     * @since 3.21.0
     */
    public static <A extends Annotation> Optional<A> getUniqueAnnotation(final Class<?> type, final Class<A> annotationCls,
            final AnnotationSearchOptions options) {
        final List<AnnotationSource<A>> sources = getAnnotationSources(type, annotationCls, options);
        return sources.size() == 1 ? Optional.of(sources.get(0).getAnnotation()) : Optional.empty();
    }

    /**
     * Gets the direct supertypes of the given type in the order used by legacy searches: superclass first, then directly implemented interfaces.
     *
     * @param type the type to inspect.
     * @return the non-null direct supertypes, never {@code null}.
     */
    private static List<Class<?>> supertypes(final Class<?> type) {
        final List<Class<?>> result = new ArrayList<>();
        if (type.getSuperclass() != null) {
            result.add(type.getSuperclass());
        }
        Collections.addAll(result, type.getInterfaces());
        return result;
    }

    /**
     * Options controlling the annotation source view lookups
     * {@link MethodUtils#getAnnotationSources(Method, Class, AnnotationSearchOptions)} and
     * {@link MethodUtils#getAnnotationSources(Class, Class, AnnotationSearchOptions)}.
     *
     * <p>
     * The defaults ({@link #DIRECT}) deliberately mirror
     * {@code getAnnotation(element, annotationCls, false, false)}: only the queried element is inspected, so callers that do not opt in keep the
     * established direct-only behavior.
     * </p>
     *
     * @since 3.21.0
     */
    public static final class AnnotationSearchOptions {

        /** Direct lookup on the queried element only; inaccessible members are skipped, matching the historical defaults. */
        public static final AnnotationSearchOptions DIRECT = new AnnotationSearchOptions(false, false);

        /** Walk the full superclass and interface graph. */
        public static final AnnotationSearchOptions SUPERTYPES = new AnnotationSearchOptions(true, false);

        /** Walk the full superclass and interface graph, including non-public members. */
        public static final AnnotationSearchOptions SUPERTYPES_IGNORE_ACCESS = new AnnotationSearchOptions(true, true);

        /**
         * Creates options that search supertypes.
         *
         * @param ignoreAccess whether to include non-accessible members.
         * @return a new options instance.
         */
        public static AnnotationSearchOptions searchSupers(final boolean ignoreAccess) {
            return new AnnotationSearchOptions(true, ignoreAccess);
        }

        private final boolean searchSupers;

        private final boolean ignoreAccess;

        private AnnotationSearchOptions(final boolean searchSupers, final boolean ignoreAccess) {
            this.searchSupers = searchSupers;
            this.ignoreAccess = ignoreAccess;
        }

        /**
         * Tests whether the lookup walks superclasses and interfaces.
         *
         * @return {@code true} if supertypes are searched.
         */
        public boolean isSearchSupers() {
            return searchSupers;
        }

        /**
         * Tests whether non-accessible members are included.
         *
         * @return {@code true} if access checks are suppressed.
         */
        public boolean isIgnoreAccess() {
            return ignoreAccess;
        }
    }

    private static Method getInvokeMethod(final boolean forceAccess, final String methodName, final Class<?>[] parameterTypes, final Class<?> cls) {
        final Method method;
        if (forceAccess) {
            method = getMatchingMethod(cls, methodName, parameterTypes);
            AccessibleObjects.setAccessible(method);
        } else {
            method = getMatchingAccessibleMethod(cls, methodName, parameterTypes);
        }
        return method;
    }

    /**
     * Gets an accessible method that matches the given name and has compatible parameters. Compatible parameters mean that every method parameter is assignable
     * from the given parameters. In other words, it finds a method with the given name that will take the parameters given.
     *
     * <p>
     * This method is used by {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     * <p>
     * This method can match primitive parameter by passing in wrapper classes. For example, a {@link Boolean} will match a primitive {@code boolean} parameter.
     * </p>
     *
     * @param cls            find method in this class.
     * @param methodName     find method with this name.
     * @param requestTypes find method with most compatible parameters.
     * @return The accessible method or null.
     * @throws SecurityException Thrown if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     */
    public static Method getMatchingAccessibleMethod(final Class<?> cls, final String methodName, final Class<?>... requestTypes) {
        final Method candidate = getMethodObject(cls, methodName, requestTypes);
        if (candidate != null) {
            // The exact match may be declared on a non-public class, so prefer the public
            // declaration the way the search below does; a static method hides, so it is kept.
            final Method accessibleCandidate = MemberUtils.isStatic(candidate) ? null : getAccessibleMethod(cls, candidate);
            return MemberUtils.setAccessibleWorkaround(accessibleCandidate != null ? accessibleCandidate : candidate);
        }
        // search through all methods
        final Method[] methods = cls.getMethods();
        final List<Method> matchingMethods = Stream.of(methods)
                .filter(method -> method.getName().equals(methodName) && MemberUtils.isMatchingMethod(method, requestTypes)).collect(Collectors.toList());
        // Sort methods by signature to force deterministic result
        matchingMethods.sort(METHOD_BY_SIGNATURE);
        Method bestMatch = null;
        for (final Method method : matchingMethods) {
            // get accessible version of method
            final Method accessibleMethod = getAccessibleMethod(method);
            if (accessibleMethod != null && (bestMatch == null || MemberUtils.compareMethodFit(accessibleMethod, bestMatch, requestTypes) < 0)) {
                bestMatch = accessibleMethod;
            }
        }
        if (bestMatch != null) {
            MemberUtils.setAccessibleWorkaround(bestMatch);
            if (bestMatch.isVarArgs()) {
                final Class<?>[] bestMatchParameterTypes = bestMatch.getParameterTypes();
                final Class<?> varArgType = bestMatchParameterTypes[bestMatchParameterTypes.length - 1].getComponentType();
                for (int paramIdx = bestMatchParameterTypes.length - 1; paramIdx < requestTypes.length; paramIdx++) {
                    final Class<?> parameterType = requestTypes[paramIdx];
                    if (!ClassUtils.isAssignable(parameterType, varArgType, true)) {
                        return null;
                    }
                }
            }
        }
        return bestMatch;
    }

    /**
     * Gets a method whether or not it's accessible. If no such method can be found, return {@code null}.
     *
     * @param cls            The class that will be subjected to the method search.
     * @param methodName     The method that we wish to call.
     * @param parameterTypes Argument class types.
     * @throws IllegalStateException Thrown if there is no unique result.
     * @throws NullPointerException  Thrown if the class is {@code null}.
     * @return The method.
     * @since 3.5
     */
    public static Method getMatchingMethod(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        Objects.requireNonNull(cls, "cls");
        Validate.notEmpty(methodName, "methodName");
        final List<Method> methods = Stream.of(cls.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .collect(Collectors.toList());
        final List<Class<?>> allSuperclassesAndInterfaces = getAllSuperclassesAndInterfaces(cls);
        Collections.reverse(allSuperclassesAndInterfaces);
        allSuperclassesAndInterfaces.stream()
                .map(Class::getDeclaredMethods)
                .flatMap(Stream::of)
                .filter(method -> method.getName().equals(methodName))
                .forEach(methods::add);
        for (final Method method : methods) {
            if (Arrays.deepEquals(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        final TreeMap<Integer, List<Method>> candidates = new TreeMap<>();
        methods.stream()
            .filter(method -> ClassUtils.isAssignable(parameterTypes, method.getParameterTypes(), true))
            .forEach(method -> {
                 final int distance = distance(parameterTypes, method.getParameterTypes());
                 final List<Method> candidatesAtDistance = candidates.computeIfAbsent(distance, k -> new ArrayList<>());
                 candidatesAtDistance.add(method);
        });
        if (candidates.isEmpty()) {
            return null;
        }
        final List<Method> bestCandidates = candidates.values().iterator().next();
        if (bestCandidates.size() == 1 || !Objects.equals(bestCandidates.get(0).getDeclaringClass(),
                bestCandidates.get(1).getDeclaringClass())) {
            return bestCandidates.get(0);
        }
        throw new IllegalStateException(String.format("Found multiple candidates for method %s on class %s : %s",
                methodName + Stream.of(parameterTypes).map(String::valueOf).collect(Collectors.joining(",", "(", ")")), cls.getName(),
                bestCandidates.stream().map(Method::toString).collect(Collectors.joining(",", "[", "]"))));
    }

    /**
     * Gets a Method, or {@code null} if a checked {@link Class#getMethod(String, Class...) } exception is thrown.
     *
     * @param cls            Receiver for {@link Class#getMethod(String, Class...)}.
     * @param name           The name of the method.
     * @param parameterTypes The list of parameters.
     * @return A Method or {@code null}.
     * @see SecurityManager#checkPermission
     * @see Class#getMethod(String, Class...)
     * @since 3.15.0
     */
    public static Method getMethodObject(final Class<?> cls, final String name, final Class<?>... parameterTypes) {
        try {
            return name != null && cls != null ? cls.getMethod(name, parameterTypes) : null;
        } catch (final NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /**
     * Gets all class level public methods of the given class that are annotated with the given annotation.
     *
     * @param cls           The {@link Class} to query.
     * @param annotationCls The {@link Annotation} that must be present on a method to be matched.
     * @return A list of Methods (possibly empty).
     * @throws NullPointerException Thrown if the class or annotation are {@code null}.
     * @since 3.4
     */
    public static List<Method> getMethodsListWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        return getMethodsListWithAnnotation(cls, annotationCls, false, false);
    }

    /**
     * Gets all methods of the given class that are annotated with the given annotation.
     *
     * @param cls           The {@link Class} to query.
     * @param annotationCls The {@link Annotation} that must be present on a method to be matched.
     * @param searchSupers  determines if a lookup in the entire inheritance hierarchy of the given class should be performed.
     * @param ignoreAccess  determines if non-public methods should be considered.
     * @return A list of Methods (possibly empty).
     * @throws NullPointerException Thrown if either the class or annotation class is {@code null}.
     * @since 3.6
     */
    public static List<Method> getMethodsListWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls, final boolean searchSupers,
            final boolean ignoreAccess) {
        Objects.requireNonNull(cls, "cls");
        Objects.requireNonNull(annotationCls, "annotationCls");
        final List<Class<?>> classes = searchSupers ? getAllSuperclassesAndInterfaces(cls) : new ArrayList<>();
        classes.add(0, cls);
        final List<Method> annotatedMethods = new ArrayList<>();
        classes.forEach(acls -> {
            final Method[] methods = ignoreAccess ? acls.getDeclaredMethods() : acls.getMethods();
            Stream.of(methods).filter(method -> method.isAnnotationPresent(annotationCls)).forEachOrdered(annotatedMethods::add);
        });
        return annotatedMethods;
    }

    /**
     * Gets all class level public methods of the given class that are annotated with the given annotation.
     *
     * @param cls           The {@link Class} to query.
     * @param annotationCls The {@link java.lang.annotation.Annotation} that must be present on a method to be matched.
     * @return An array of Methods (possibly empty).
     * @throws NullPointerException Thrown if the class or annotation are {@code null}.
     * @since 3.4
     */
    public static Method[] getMethodsWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        return getMethodsWithAnnotation(cls, annotationCls, false, false);
    }

    /**
     * Gets all methods of the given class that are annotated with the given annotation.
     *
     * @param cls           The {@link Class} to query.
     * @param annotationCls The {@link java.lang.annotation.Annotation} that must be present on a method to be matched.
     * @param searchSupers  determines if a lookup in the entire inheritance hierarchy of the given class should be performed.
     * @param ignoreAccess  determines if non-public methods should be considered.
     * @return An array of Methods (possibly empty).
     * @throws NullPointerException Thrown if the class or annotation are {@code null}.
     * @since 3.6
     */
    public static Method[] getMethodsWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls, final boolean searchSupers,
            final boolean ignoreAccess) {
        return getMethodsListWithAnnotation(cls, annotationCls, searchSupers, ignoreAccess).toArray(ArrayUtils.EMPTY_METHOD_ARRAY);
    }

    /**
     * Gets the hierarchy of overridden methods down to {@code result} respecting generics.
     *
     * @param method lowest to consider.
     * @param interfacesBehavior whether to search interfaces, {@code null} {@code implies} false.
     * @return A {@code Set<Method>} in ascending order from subclass to superclass.
     * @throws NullPointerException Thrown if the specified method is {@code null}.
     * @throws SecurityException Thrown if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     * @since 3.2
     */
    public static Set<Method> getOverrideHierarchy(final Method method, final Interfaces interfacesBehavior) {
        Objects.requireNonNull(method, "method");
        final Set<Method> result = new LinkedHashSet<>();
        result.add(method);
        final Class<?>[] parameterTypes = method.getParameterTypes();
        final Class<?> declaringClass = method.getDeclaringClass();
        final Iterator<Class<?>> hierarchy = ClassUtils.hierarchy(declaringClass, interfacesBehavior).iterator();
        //skip the declaring class :P
        hierarchy.next();
        hierarchyTraversal: while (hierarchy.hasNext()) {
            final Class<?> c = hierarchy.next();
            final Method m = getMatchingAccessibleMethod(c, method.getName(), parameterTypes);
            if (m == null) {
                continue;
            }
            if (Arrays.equals(m.getParameterTypes(), parameterTypes)) {
                // matches without generics
                result.add(m);
                continue;
            }
            // necessary to get arguments every time in the case that we are including interfaces
            final Map<TypeVariable<?>, Type> typeArguments = TypeUtils.getTypeArguments(declaringClass, m.getDeclaringClass());
            for (int i = 0; i < parameterTypes.length; i++) {
                final Type childType = TypeUtils.unrollVariables(typeArguments, method.getGenericParameterTypes()[i]);
                final Type parentType = TypeUtils.unrollVariables(typeArguments, m.getGenericParameterTypes()[i]);
                if (!TypeUtils.equals(childType, parentType)) {
                    continue hierarchyTraversal;
                }
            }
            result.add(m);
        }
        return result;
    }

    /**
     * Invokes a method whose parameter types match exactly the object type.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object     invoke method on this object.
     * @param methodName get method with this name.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @since 3.4
     */
    public static Object invokeExactMethod(final Object object, final String methodName)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeExactMethod(object, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a method whose parameter types match exactly the object types.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object     invoke method on this object.
     * @param methodName get method with this name.
     * @param args       use these arguments - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactMethod(final Object object, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeExactMethod(object, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a method whose parameter types match exactly the parameter types given.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object         Invokes a method on this object.
     * @param methodName     Gets a method with this name.
     * @param args           Method arguments - treat null as empty array.
     * @param parameterTypes Match these parameters - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactMethod(final Object object, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?> cls = Objects.requireNonNull(object, "object").getClass();
        final Class<?>[] paramTypes = ArrayUtils.nullToEmpty(parameterTypes);
        final Method method = getAccessibleMethod(cls, methodName, paramTypes);
        requireNonNull(method, cls, methodName, paramTypes);
        return method.invoke(object, ArrayUtils.nullToEmpty(args));
    }

    /**
     * Invokes a {@code static} method whose parameter types match exactly the object types.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param cls        invoke static method on this class.
     * @param methodName get method with this name.
     * @param args       use these arguments - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactStaticMethod(final Class<?> cls, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeExactStaticMethod(cls, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a {@code static} method whose parameter types match exactly the parameter types given.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param cls            invoke static method on this class.
     * @param methodName     get method with this name.
     * @param args           use these arguments - treat {@code null} as empty array.
     * @param parameterTypes match these parameters - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactStaticMethod(final Class<?> cls, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?>[] paramTypes = ArrayUtils.nullToEmpty(parameterTypes);
        final Method method = getAccessibleMethod(cls, methodName, ArrayUtils.nullToEmpty(paramTypes));
        requireNonNull(method, cls, methodName, paramTypes);
        return method.invoke(null, ArrayUtils.nullToEmpty(args));
    }

    /**
     * Invokes a named method without parameters.
     *
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, boolean forceAccess, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object.
     * @param forceAccess force access to invoke method even if it's not accessible.
     * @param methodName get method with this name.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     * @since 3.5
     */
    public static Object invokeMethod(final Object object, final boolean forceAccess, final String methodName)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, forceAccess, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, boolean forceAccess, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object.
     * @param forceAccess force access to invoke method even if it's not accessible.
     * @param methodName get method with this name.
     * @param args use these arguments - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     * @since 3.5
     */
    public static Object invokeMethod(final Object object, final boolean forceAccess, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeMethod(object, forceAccess, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     *
     * @param object invoke method on this object.
     * @param forceAccess force access to invoke method even if it's not accessible.
     * @param methodName get method with this name.
     * @param args use these arguments - treat null as empty array.
     * @param parameterTypes match these parameters - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     * @since 3.5
     */
    public static Object invokeMethod(final Object object, final boolean forceAccess, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?> cls = Objects.requireNonNull(object, "object").getClass();
        final Class<?>[] paramTypes = ArrayUtils.nullToEmpty(parameterTypes);
        final Method method = getInvokeMethod(forceAccess, methodName, paramTypes, cls);
        requireNonNull(method, cls, methodName, paramTypes);
        return method.invoke(object, toVarArgs(method, ArrayUtils.nullToEmpty(args)));
    }

    /**
     * Invokes a named method without parameters.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object.
     * @param methodName get method with this name.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException Thrown if there is no such accessible method.
     * @throws InvocationTargetException Thrown to wrap an exception thrown by the method invoked.
     * @throws IllegalAccessException Thrown if the requested method is not accessible via reflection.
     * @throws SecurityException Thrown if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     * @since 3.4
     */
    public static Object invokeMethod(final Object object, final String methodName) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object.
     * @param methodName get method with this name.
     * @param args use these arguments - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException Thrown if there is no such accessible method.
     * @throws InvocationTargetException Thrown to wrap an exception thrown by the method invoked.
     * @throws IllegalAccessException Thrown if the requested method is not accessible via reflection.
     * @throws NullPointerException Thrown if the object or method name are {@code null}.
     * @throws SecurityException Thrown if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeMethod(final Object object, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeMethod(object, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     *
     * @param object invoke method on this object.
     * @param methodName get method with this name.
     * @param args use these arguments - treat null as empty array.
     * @param parameterTypes match these parameters - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeMethod(final Object object, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, false, methodName, args, parameterTypes);
    }

    /**
     * Invokes a named {@code static} method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} class
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeStaticMethod(Class, String, Object[], Class[])}.
     * </p>
     *
     * @param cls invoke static method on this class.
     * @param methodName get method with this name.
     * @param args use these arguments - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeStaticMethod(cls, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named {@code static} method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} class
     * would match a {@code boolean} primitive.
     * </p>
     *
     * @param cls invoke static method on this class.
     * @param methodName get method with this name.
     * @param args use these arguments - treat {@code null} as empty array.
     * @param parameterTypes match these parameters - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?>[] paramTypes = ArrayUtils.nullToEmpty(parameterTypes);
        final Method method = getMatchingAccessibleMethod(cls, methodName, paramTypes);
        requireNonNull(method, cls, methodName, paramTypes);
        return method.invoke(null, toVarArgs(method, ArrayUtils.nullToEmpty(args)));
    }

    private static Method requireNonNull(final Method method, final Class<?> cls, final String methodName, final Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        if (method == null) {
            throw new NoSuchMethodException(String.format("No method: %s.%s(%s)", ClassUtils.getName(cls), methodName,
                    Streams.of(parameterTypes).map(ClassUtils::getName).collect(LangCollectors.joining(", "))));
        }
        return method;
    }

    static Object[] toVarArgs(final Executable executable, final Object[] args)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        return executable.isVarArgs() ? toVarArgs(args, executable.getParameterTypes()) : args;
    }

    /**
     * Gets an array of arguments in the canonical form, given an arguments array passed to a varargs method, for example an array with the declared number of
     * parameters, and whose last parameter is an array of the varargs type.
     * <p>
     * We follow the <a href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html#jls-5.1.2">JLS 5.1.2. Widening Primitive Conversion</a> rules.
     * </p>
     *
     * @param args                 The array of arguments passed to the varags method.
     * @param methodParameterTypes The declared array of method parameter types.
     * @return An array of the variadic arguments passed to the method.
     * @throws NoSuchMethodException       Thrown if the constructor could not be found.
     * @throws IllegalAccessException      Thrown if this {@code Constructor} object is enforcing Java language access control and the underlying constructor is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if the number of actual and formal parameters differ; if an unwrapping conversion for primitive arguments
     *                                     fails; or if, after possible unwrapping, a parameter value cannot be converted to the corresponding formal parameter
     *                                     type by a method invocation conversion; if this constructor pertains to an enum type.
     * @throws InstantiationException      Thrown if a class that declares the underlying constructor represents an abstract class.
     * @throws InvocationTargetException   Thrown if an underlying constructor throws an exception.
     * @throws ExceptionInInitializerError Thrown if an initialization provoked by this method fails.
     * @see <a href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html#jls-5.1.2">JLS 5.1.2. Widening Primitive Conversion</a>
     */
    private static Object[] toVarArgs(final Object[] args, final Class<?>[] methodParameterTypes)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        final int mptLength = methodParameterTypes.length;
        if (args.length == mptLength) {
            final Object lastArg = args[args.length - 1];
            if (lastArg == null || lastArg.getClass().equals(methodParameterTypes[mptLength - 1])) {
                // The args array is already in the canonical form for the method.
                return args;
            }
        }
        // Construct a new array matching the method's declared parameter types.
        // Copy the normal (non-varargs) parameters
        final Object[] newArgs = ArrayUtils.arraycopy(args, 0, 0, mptLength - 1, () -> new Object[mptLength]);
        // Construct a new array for the variadic parameters
        final Class<?> varArgComponentType = methodParameterTypes[mptLength - 1].getComponentType();
        final Class<?> varArgComponentWrappedType = ClassUtils.primitiveToWrapper(varArgComponentType);
        final int varArgLength = args.length - mptLength + 1;
        // Copy the variadic arguments into the varargs array, converting types if needed.
        Object varArgsArray = Array.newInstance(varArgComponentWrappedType, varArgLength);
        final boolean primitiveOrWrapper = ClassUtils.isPrimitiveOrWrapper(varArgComponentWrappedType);
        for (int i = 0; i < varArgLength; i++) {
            final Object arg = args[mptLength - 1 + i];
            try {
                Array.set(varArgsArray, i, primitiveOrWrapper
                        ? varArgComponentWrappedType.getConstructor(ClassUtils.wrapperToPrimitive(varArgComponentWrappedType)).newInstance(arg)
                        : varArgComponentWrappedType.cast(arg));
            } catch (final InstantiationException e) {
                throw new IllegalArgumentException("Cannot convert vararg #" + i, e);
            }
        }
        if (varArgComponentType.isPrimitive()) {
            // unbox from wrapper type to primitive type
            varArgsArray = ArrayUtils.toPrimitive(varArgsArray);
        }
        // Store the varargs array in the last position of the array to return
        newArgs[mptLength - 1] = varArgsArray;
        // Return the canonical varargs array.
        return newArgs;
    }

    /**
     * {@link MethodUtils} instances should NOT be constructed in standard programming. Instead, the class should be used as
     * {@code MethodUtils.getAccessibleMethod(method)}.
     *
     * <p>
     * This constructor is {@code public} to permit tools that require a JavaBean instance to operate.
     * </p>
     *
     * @deprecated TODO Make private in 4.0.
     */
    @Deprecated
    public MethodUtils() {
        // empty
    }
}
