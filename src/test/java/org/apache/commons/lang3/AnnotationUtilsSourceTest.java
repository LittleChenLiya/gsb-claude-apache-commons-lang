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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.AnnotationUtils.AnnotationSource;
import org.junit.jupiter.api.Test;

/**
 * Tests the opt-in annotation source view of {@link AnnotationUtils}:
 * {@link AnnotationUtils#getAnnotationSources(Method, Class)} follows the
 * override relationship, ignores bridge duplicates and reports all
 * candidates of an interface diamond.
 */
class AnnotationUtilsSourceTest extends AbstractLangTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Mark {
        String value();
    }

    // Micro type 1: interface diamond, two conflicting annotated declarations.
    interface DiamondLeft {
        @Mark("left")
        String value();
    }

    interface DiamondRight {
        @Mark("right")
        String value();
    }

    static class DiamondImpl implements DiamondLeft, DiamondRight {
        @Override
        public String value() {
            return "diamond";
        }
    }

    // Micro type 2: generic interface implemented with a concrete type, forcing a bridge method.
    interface Repository<T> {
        @Mark("repository")
        void save(T entity);
    }

    static class StringRepository implements Repository<String> {
        @Override
        public void save(final String entity) {
            // empty
        }
    }

    // Micro type 3: same name, different parameters - not an override.
    static class OverloadBase {
        @Mark("base")
        void run(final int count) {
            // empty
        }
    }

    static class OverloadSub extends OverloadBase {
        void run(final String name) {
            // empty
        }
    }

    // Legal single source: plain override chain.
    static class ChainBase {
        @Mark("base")
        void call() {
            // empty
        }
    }

    static class ChainSub extends ChainBase {
        @Override
        void call() {
            // empty
        }
    }

    @Test
    void interfaceDiamondReturnsAllCandidates() throws Exception {
        final Method method = DiamondImpl.class.getMethod("value");
        final List<AnnotationSource> sources = AnnotationUtils.getAnnotationSources(method, Mark.class);
        assertEquals(2, sources.size(), "diamond conflict must report all candidates");
        final Set<String> values = sources.stream()
                .map(source -> ((Mark) source.getAnnotation()).value())
                .collect(Collectors.toSet());
        assertEquals(Set.of("left", "right"), values);
        final Set<Class<?>> declaring = sources.stream()
                .map(source -> source.getSource().getDeclaringClass())
                .collect(Collectors.toSet());
        assertEquals(Set.of(DiamondLeft.class, DiamondRight.class), declaring);
        for (final AnnotationSource source : sources) {
            assertEquals(method, source.getMethod());
            assertEquals(2, source.getPath().size());
            assertEquals(method, source.getPath().get(0));
            assertEquals(source.getSource(), source.getPath().get(1));
        }
    }

    @Test
    void genericBridgeProducesSingleSourceWithoutBridgeDuplicates() throws Exception {
        final Method concrete = StringRepository.class.getMethod("save", String.class);
        final Method expected = Repository.class.getMethod("save", Object.class);
        final List<AnnotationSource> sources = AnnotationUtils.getAnnotationSources(concrete, Mark.class);
        assertEquals(1, sources.size(), "bridge duplicates must be ignored");
        final AnnotationSource source = sources.get(0);
        assertEquals(expected, source.getSource());
        assertEquals(List.of(concrete, expected), source.getPath());
        assertTrue(source.getPath().stream().noneMatch(Method::isBridge), "no bridge method in the path");
        assertEquals("repository", ((Mark) source.getAnnotation()).value());
    }

    @Test
    void queryingBridgeMethodResolvesToSameSingleSource() throws Exception {
        final Method bridge = StringRepository.class.getMethod("save", Object.class);
        assertTrue(bridge.isBridge(), "fixture must actually contain a bridge method");
        final List<AnnotationSource> sources = AnnotationUtils.getAnnotationSources(bridge, Mark.class);
        assertEquals(1, sources.size());
        assertEquals(Repository.class.getMethod("save", Object.class), sources.get(0).getSource());
        assertFalse(sources.get(0).getMethod().isBridge(), "view must be based on the non-bridge method");
        assertTrue(sources.get(0).getPath().stream().noneMatch(Method::isBridge));
    }

    @Test
    void sameNameDifferentParametersIsNotAnOverride() throws Exception {
        final Method overload = OverloadSub.class.getDeclaredMethod("run", String.class);
        assertTrue(AnnotationUtils.getAnnotationSources(overload, Mark.class).isEmpty(),
                "same-name method with different parameters must not be a source");
        final Method own = OverloadBase.class.getDeclaredMethod("run", int.class);
        final List<AnnotationSource> sources = AnnotationUtils.getAnnotationSources(own, Mark.class);
        assertEquals(1, sources.size());
        assertEquals(List.of(own), sources.get(0).getPath());
        assertEquals("base", ((Mark) sources.get(0).getAnnotation()).value());
    }

    @Test
    void overrideChainYieldsLegalSingleSource() throws Exception {
        final Method sub = ChainSub.class.getDeclaredMethod("call");
        final Method base = ChainBase.class.getDeclaredMethod("call");
        final List<AnnotationSource> sources = AnnotationUtils.getAnnotationSources(sub, Mark.class);
        assertEquals(1, sources.size(), "unambiguous chain must yield exactly one source");
        assertEquals(List.of(sub, base), sources.get(0).getPath());
        assertEquals(base, sources.get(0).getSource());
        assertEquals("base", ((Mark) sources.get(0).getAnnotation()).value());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> AnnotationUtils.getAnnotationSources(null, Mark.class));
        assertThrows(NullPointerException.class,
                () -> AnnotationUtils.getAnnotationSources(ChainBase.class.getDeclaredMethod("call"), null));
    }
}
