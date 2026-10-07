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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the annotation inheritance source view:
 * interface diamonds, generic bridges, and same-name different-argument overloads.
 */
class AnnotationSourceTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface Marked {
    }

    // ---- Micro-type 1: interface diamond --------------------------------------

    interface DiamondTop {
        void op();
    }

    interface DiamondLeft extends DiamondTop {
        @Override
        @Marked
        void op();
    }

    interface DiamondRight extends DiamondTop {
        @Override
        @Marked
        void op();
    }

    static class DiamondImpl implements DiamondLeft, DiamondRight {
        @Override
        public void op() {
        }
    }

    // Method diamond whose middle interfaces only inherit (do not re-declare) the method; the
    // two branches merge at the single re-declaring top, which must remain one legal source.
    interface MergeTop {
        @Marked
        void merge();
    }

    interface MergeLeft extends MergeTop {
    }

    interface MergeRight extends MergeTop {
    }

    static class MergeImpl implements MergeLeft, MergeRight {
        @Override
        public void merge() {
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface TypeMarked {
    }

    @TypeMarked
    interface TypeTop {
    }

    interface TypeLeft extends TypeTop {
    }

    interface TypeRight extends TypeTop {
    }

    static class TypeDiamondImpl implements TypeLeft, TypeRight {
    }

    // ---- Micro-type 2: generic bridge -----------------------------------------

    interface GenericBox<T> {
        void put(T value);
    }

    static class StringBox implements GenericBox<String> {
        @Override
        @Marked
        public void put(final String value) {
        }
    }

    static class GenericParent2<T> implements GenericBox<T> {
        @Override
        public void put(final T value) {
        }
    }

    static class StringChild extends GenericParent2<String> {
        @Override
        @Marked
        public void put(final String value) {
        }
    }

    // ---- Micro-type 3: same name, different parameters ------------------------

    static class OverloadBase {
        @Marked
        public void handle(final String value) {
        }

        public void handle(final Integer value) {
        }
    }

    static class OverloadChild extends OverloadBase {
        @Override
        public void handle(final String value) {
        }

        @Override
        public void handle(final Integer value) {
        }
    }

    private static String names(final AnnotationSource<?> source) {
        return source.getPath().stream()
                .map(e -> e instanceof Class ? ((Class<?>) e).getSimpleName()
                        : e instanceof Method
                                ? ((Method) e).getDeclaringClass().getSimpleName() + "#" + ((Method) e).getName()
                                : e.toString())
                .collect(Collectors.joining(" -> "));
    }

    @Test
    void testInterfaceDiamondReturnsAllCandidatesAndNoArbitraryChoice() throws Exception {
        final Method op = DiamondImpl.class.getMethod("op");
        final List<AnnotationSource<Marked>> sources = MethodUtils
                .getAnnotationSources(op, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);

        // Both independently annotated branches are returned; nothing is silently picked.
        assertEquals(2, sources.size(), () -> describe(sources));
        assertTrue(sources.stream().allMatch(s -> s.getSource() instanceof Method));
        final long declaringCount = sources.stream().map(AnnotationSource::getSource).distinct().count();
        assertEquals(2, declaringCount);

        // Each candidate carries the inheritance path from the impl to the declaring interface.
        assertEquals("DiamondImpl#op -> DiamondLeft#op", names(sources.get(0)));
        assertEquals("DiamondImpl#op -> DiamondRight#op", names(sources.get(1)));

        // Two competing sources: a unique lookup must not arbitrarily choose one.
        assertFalse(MethodUtils.getUniqueAnnotation(op, Marked.class,
                MethodUtils.AnnotationSearchOptions.SUPERTYPES).isPresent());
    }

    @Test
    void testMethodDiamondMergeKeepsSingleLegalSource() throws Exception {
        final Method merge = MergeImpl.class.getMethod("merge");
        final List<AnnotationSource<Marked>> sources = MethodUtils
                .getAnnotationSources(merge, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);

        // Both branches reach the same re-declaring member MergeTop#merge: reported once, not duplicated.
        assertEquals(1, sources.size(), () -> describe(sources));
        assertEquals(MergeTop.class, ((Method) sources.get(0).getSource()).getDeclaringClass());
        assertTrue(MethodUtils.getUniqueAnnotation(merge, Marked.class,
                MethodUtils.AnnotationSearchOptions.SUPERTYPES).isPresent());
    }

    @Test
    void testTypeDiamondMergesToOneSourceWithFullPath() {
        final List<AnnotationSource<TypeMarked>> sources = MethodUtils
                .getAnnotationSources(TypeDiamondImpl.class, TypeMarked.class,
                        MethodUtils.AnnotationSearchOptions.SUPERTYPES);

        // The diamond merges at TypeTop: one legal single source, reached once.
        assertEquals(1, sources.size(), () -> describe(sources));
        assertEquals(TypeTop.class, sources.get(0).getSource());
        assertEquals("TypeDiamondImpl -> TypeLeft -> TypeTop", names(sources.get(0)));
        assertTrue(MethodUtils.getUniqueAnnotation(TypeDiamondImpl.class, TypeMarked.class,
                MethodUtils.AnnotationSearchOptions.SUPERTYPES).isPresent());
    }

    @Test
    void testGenericBridgeIsNotDuplicated() throws Exception {
        final Method put = StringBox.class.getMethod("put", String.class);
        final List<AnnotationSource<Marked>> sources = MethodUtils
                .getAnnotationSources(put, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);

        // The bridge method put(Object) must not appear as an extra source.
        assertEquals(1, sources.size(), () -> describe(sources));
        final Method sourceMethod = (Method) sources.get(0).getSource();
        assertFalse(sourceMethod.isBridge());
        assertEquals(StringBox.class, sourceMethod.getDeclaringClass());
        assertEquals("StringBox#put", names(sources.get(0)));

        final Method inherited = StringChild.class.getMethod("put", String.class);
        final List<AnnotationSource<Marked>> inheritedSources = MethodUtils
                .getAnnotationSources(inherited, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);
        // StringChild.put(String) is annotated; the erased bridge path put(Object) is skipped.
        assertEquals(1, inheritedSources.size(), () -> describe(inheritedSources));
        assertFalse(((Method) inheritedSources.get(0).getSource()).isBridge());
    }

    @Test
    void testSameNameDifferentParametersFollowsOnlyTrueOverride() throws Exception {
        final Method integerHandle = OverloadChild.class.getMethod("handle", Integer.class);
        final List<AnnotationSource<Marked>> sources = MethodUtils
                .getAnnotationSources(integerHandle, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);

        // handle(Integer) is not annotated; handle(String) annotation must not leak across overloads.
        assertTrue(sources.isEmpty(), () -> describe(sources));
        assertFalse(MethodUtils.getUniqueAnnotation(integerHandle, Marked.class,
                MethodUtils.AnnotationSearchOptions.SUPERTYPES).isPresent());

        final Method stringHandle = OverloadChild.class.getMethod("handle", String.class);
        final List<AnnotationSource<Marked>> stringSources = MethodUtils
                .getAnnotationSources(stringHandle, Marked.class, MethodUtils.AnnotationSearchOptions.SUPERTYPES);
        assertEquals(1, stringSources.size(), () -> describe(stringSources));
        assertEquals(OverloadBase.class, ((Method) stringSources.get(0).getSource()).getDeclaringClass());
        assertEquals("OverloadChild#handle -> OverloadBase#handle", names(stringSources.get(0)));
    }

    @Test
    void testDirectModeKeepsLegacyBehaviorWhenOptionNotEnabled() throws Exception {
        final Method op = DiamondImpl.class.getMethod("op");
        // Options disabled: only the queried element itself is inspected, like the historical default.
        assertTrue(MethodUtils.getAnnotationSources(op, Marked.class, MethodUtils.AnnotationSearchOptions.DIRECT)
                .isEmpty());
        assertTrue(MethodUtils.getAnnotationSources(op, Marked.class, null).isEmpty());
        // The pre-existing MethodUtils API behavior is unchanged.
        final Method stringHandle = OverloadChild.class.getMethod("handle", String.class);
        assertEquals(OverloadBase.class.getDeclaredMethod("handle", String.class).getAnnotation(Marked.class),
                MethodUtils.getAnnotation(stringHandle, Marked.class, true, true));
    }

    private static String describe(final List<? extends AnnotationSource<?>> sources) {
        return sources.stream().map(AnnotationSourceTest::names).collect(Collectors.joining("; "));
    }

    /**
     * Writes a compact report of candidate paths into the JUnit-managed temporary directory.
     */
    @Test
    void writeEvidenceToTempFolder(@TempDir final Path tempDir) throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append("method diamond: ").append(describe(MethodUtils
                .getAnnotationSources(DiamondImpl.class.getMethod("op"), Marked.class,
                        MethodUtils.AnnotationSearchOptions.SUPERTYPES))).append(System.lineSeparator());
        sb.append("type diamond:   ").append(describe(MethodUtils
                .getAnnotationSources(TypeDiamondImpl.class, TypeMarked.class,
                        MethodUtils.AnnotationSearchOptions.SUPERTYPES))).append(System.lineSeparator());
        sb.append("generic bridge: ").append(describe(MethodUtils
                .getAnnotationSources(StringBox.class.getMethod("put", String.class), Marked.class,
                        MethodUtils.AnnotationSearchOptions.SUPERTYPES))).append(System.lineSeparator());
        sb.append("overload:       ").append(describe(MethodUtils
                .getAnnotationSources(OverloadChild.class.getMethod("handle", String.class), Marked.class,
                        MethodUtils.AnnotationSearchOptions.SUPERTYPES))).append(System.lineSeparator());
        final Path report = tempDir.resolve("annotation-sources.txt");
        Files.write(report, sb.toString().getBytes(StandardCharsets.UTF_8));
        final String readBack = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(readBack.contains("DiamondLeft#op"));
        assertTrue(readBack.contains("DiamondRight#op"));
        assertTrue(readBack.contains("TypeTop"));
        assertTrue(readBack.contains("StringBox#put"));
        System.out.println(readBack);
    }
}
