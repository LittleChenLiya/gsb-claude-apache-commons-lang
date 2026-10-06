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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.AbstractLangTest;
import org.apache.commons.lang3.reflect.TypeUtils.TypeBinding;
import org.apache.commons.lang3.reflect.TypeUtils.TypeBindingReport;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TypeUtils#getTypeBindingReport(Type, Class)}.
 */
class TypeUtilsBindingReportTest extends AbstractLangTest {

    interface MyList<T> extends List<T> {
    }

    interface StringList extends MyList<String> {
    }

    interface ArrayOf<T> {
    }

    interface ConfRoot<T> {
    }

    interface DiamondList extends MyList<String>, List<String> {
    }

    static class SelfBounded<T extends SelfBounded<T>> {
    }

    static class ConcreteSelf extends SelfBounded<ConcreteSelf> {
    }

    static class GenericBox<T> {
    }

    static class GenericArrayHolder<T> implements ArrayOf<T[]> {
    }

    static class StringArrayHolder implements ArrayOf<String[]> {
    }

    /** Records class initializations without being part of the reported hierarchy. */
    static class InitObserver {
        static int initializerRuns;
    }

    static class NoInitBase<T> {
    }

    static class NoInit extends NoInitBase<String> {
        static {
            InitObserver.initializerRuns++;
        }
    }

    static class WildcardHolder {
        List<? extends Number> upper;
        List<? super Integer> lower;
        List<?> unknown;
    }

    @Test
    void subclassListStringMapsToTypeVariable() {
        // Example kind 1: a subtype of List<String> maps the type variable to String along the real parent types.
        final TypeVariable<?> t = MyList.class.getTypeParameters()[0];
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(StringList.class, MyList.class);
        final List<TypeBinding> bindings = report.getBindings(t);
        assertEquals(1, bindings.size());
        assertEquals(TypeBinding.Kind.EXACT, bindings.get(0).getKind());
        assertEquals(String.class, bindings.get(0).getType());
        assertFalse(report.isUndecided());
        // The same argument propagates up to the variable of List itself.
        final TypeVariable<?> e = List.class.getTypeParameters()[0];
        final TypeBindingReport listReport = TypeUtils.getTypeBindingReport(StringList.class, List.class);
        final List<TypeBinding> listBindings = listReport.getBindings(e);
        assertEquals(1, listBindings.size());
        assertEquals(TypeBinding.Kind.EXACT, listBindings.get(0).getKind());
        assertEquals(String.class, listBindings.get(0).getType());
        assertFalse(listReport.isUndecided());
    }

    @Test
    void selfReferencingBoundTerminates() {
        // Example kind 2: a self-referencing bound is represented finitely and does not loop.
        final TypeVariable<?> t = SelfBounded.class.getTypeParameters()[0];
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(SelfBounded.class, SelfBounded.class);
        final List<TypeBinding> bindings = report.getBindings(t);
        assertTrue(bindings.stream().anyMatch(b -> b.getKind() == TypeBinding.Kind.UNRESOLVED && b.getType() == t));
        assertTrue(bindings.stream().anyMatch(b -> b.getKind() == TypeBinding.Kind.UPPER_BOUND && b.getType() instanceof ParameterizedType
                && ((ParameterizedType) b.getType()).getRawType() == SelfBounded.class));
        assertNotNull(report.toString());
        // A concrete self type resolves the variable exactly.
        final TypeBindingReport concrete = TypeUtils.getTypeBindingReport(ConcreteSelf.class, SelfBounded.class);
        final List<TypeBinding> concreteBindings = concrete.getBindings(t);
        assertEquals(1, concreteBindings.size());
        assertEquals(TypeBinding.Kind.EXACT, concreteBindings.get(0).getKind());
        assertEquals(ConcreteSelf.class, concreteBindings.get(0).getType());
        assertFalse(concrete.isUndecided());
    }

    @Test
    void unknownVariableKeepsSymbol() {
        // Example kind 3: an unresolvable variable keeps its symbol instead of being replaced by Object.
        final TypeVariable<?> t = GenericBox.class.getTypeParameters()[0];
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(GenericBox.class, GenericBox.class);
        final List<TypeBinding> bindings = report.getBindings(t);
        assertEquals(1, bindings.size());
        assertEquals(TypeBinding.Kind.UNRESOLVED, bindings.get(0).getKind());
        assertSame(t, bindings.get(0).getType());
        assertTrue(report.isUndecided());
    }

    @Test
    void objectIsNotReportedAsAnswer() {
        // A variable whose only bound is Object proves nothing about the target: undecided, no Object binding.
        final TypeVariable<?> t = GenericBox.class.getTypeParameters()[0];
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(t, List.class);
        assertTrue(report.getBindings().isEmpty());
        assertTrue(report.isUndecided());
    }

    @Test
    void wildcardKindsAreDistinguished() throws Exception {
        final TypeVariable<?> e = List.class.getTypeParameters()[0];

        final Type upperType = WildcardHolder.class.getDeclaredField("upper").getGenericType();
        final TypeBindingReport upperReport = TypeUtils.getTypeBindingReport(upperType, List.class);
        final List<TypeBinding> upperBindings = upperReport.getBindings(e);
        assertEquals(1, upperBindings.size());
        assertEquals(TypeBinding.Kind.UPPER_BOUND, upperBindings.get(0).getKind());
        assertEquals(Number.class, upperBindings.get(0).getType());
        assertFalse(upperReport.isUndecided());

        final Type lowerType = WildcardHolder.class.getDeclaredField("lower").getGenericType();
        final TypeBindingReport lowerReport = TypeUtils.getTypeBindingReport(lowerType, List.class);
        final List<TypeBinding> lowerBindings = lowerReport.getBindings(e);
        assertEquals(1, lowerBindings.size());
        assertEquals(TypeBinding.Kind.LOWER_BOUND, lowerBindings.get(0).getKind());
        assertEquals(Integer.class, lowerBindings.get(0).getType());

        final Type unknownType = WildcardHolder.class.getDeclaredField("unknown").getGenericType();
        final TypeBindingReport unknownReport = TypeUtils.getTypeBindingReport(unknownType, List.class);
        final List<TypeBinding> unknownBindings = unknownReport.getBindings(e);
        assertEquals(1, unknownBindings.size());
        assertEquals(TypeBinding.Kind.UNKNOWN, unknownBindings.get(0).getKind());
        assertNull(unknownBindings.get(0).getType());
        assertTrue(unknownReport.isUndecided());
    }

    @Test
    void genericArrayIsRepresented() {
        final TypeVariable<?> t = ArrayOf.class.getTypeParameters()[0];
        // A generic array argument is represented as a GenericArrayType.
        final TypeBindingReport generic = TypeUtils.getTypeBindingReport(GenericArrayHolder.class, ArrayOf.class);
        final List<TypeBinding> genericBindings = generic.getBindings(t);
        assertEquals(1, genericBindings.size());
        assertEquals(TypeBinding.Kind.EXACT, genericBindings.get(0).getKind());
        assertTrue(genericBindings.get(0).getType() instanceof GenericArrayType);
        assertEquals("T[]", TypeUtils.toString(genericBindings.get(0).getType()));
        // The component variable is unresolvable, so the report is undecided.
        assertTrue(generic.isUndecided());
        // A concrete array argument is represented as the array class.
        final TypeBindingReport concrete = TypeUtils.getTypeBindingReport(StringArrayHolder.class, ArrayOf.class);
        final List<TypeBinding> concreteBindings = concrete.getBindings(t);
        assertEquals(1, concreteBindings.size());
        assertEquals(TypeBinding.Kind.EXACT, concreteBindings.get(0).getKind());
        assertEquals(String[].class, concreteBindings.get(0).getType());
        assertFalse(concrete.isUndecided());
    }

    @Test
    void classInitializerIsNotRun() {
        // Reporting reads reflection metadata only: the class initializer must run 0 times.
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(NoInit.class, NoInitBase.class);
        final TypeVariable<?> t = NoInitBase.class.getTypeParameters()[0];
        assertEquals(String.class, report.getBindings(t).get(0).getType());
        assertEquals(0, InitObserver.initializerRuns);
    }

    @Test
    void conflictingPathsArePreserved() {
        // Compiled Java forbids inheriting the same generic type with different arguments, so conflicting
        // paths are exercised through the report recording API: every distinct binding is kept, exact
        // duplicates are dropped, and nothing is overwritten.
        final TypeVariable<?> t = ConfRoot.class.getTypeParameters()[0];
        final TypeBindingReport report = new TypeBindingReport(ConfRoot.class, ConfRoot.class);
        report.addBinding(new TypeBinding(t, String.class, TypeBinding.Kind.EXACT));
        report.addBinding(new TypeBinding(t, Integer.class, TypeBinding.Kind.EXACT));
        report.addBinding(new TypeBinding(t, String.class, TypeBinding.Kind.EXACT));
        final List<TypeBinding> bindings = report.getBindings(t);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.getKind() == TypeBinding.Kind.EXACT && b.getType() == String.class));
        assertTrue(bindings.stream().anyMatch(b -> b.getKind() == TypeBinding.Kind.EXACT && b.getType() == Integer.class));
        assertTrue(report.hasConflicts());
    }

    @Test
    void convergingPathsAreDeduplicated() {
        // DiamondList reaches List<String> through two real parent paths; the same binding is recorded once.
        final TypeVariable<?> e = List.class.getTypeParameters()[0];
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(DiamondList.class, List.class);
        final List<TypeBinding> bindings = report.getBindings(e);
        assertEquals(1, bindings.size());
        assertEquals(TypeBinding.Kind.EXACT, bindings.get(0).getKind());
        assertEquals(String.class, bindings.get(0).getType());
        assertFalse(report.hasConflicts());
        assertFalse(report.isUndecided());
    }

    @Test
    void notAssignableIsUndecided() {
        final TypeBindingReport report = TypeUtils.getTypeBindingReport(StringList.class, Map.class);
        assertTrue(report.isUndecided());
        assertTrue(report.getBindings().isEmpty());
    }

    @Test
    void normalIsAssignableStillWorks() {
        assertTrue(TypeUtils.isAssignable(StringList.class, List.class));
        assertFalse(TypeUtils.isAssignable(StringList.class, Map.class));
        assertTrue(TypeUtils.isAssignable(String.class, Object.class));
    }
}
