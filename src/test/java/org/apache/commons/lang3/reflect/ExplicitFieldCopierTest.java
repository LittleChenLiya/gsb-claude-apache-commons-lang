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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ExplicitFieldCopier} with two tiny generic classes.
 *
 * <p>
 * The acceptance scenarios are: compatible generic fields are copied and only
 * the declared fields in the explicit set are touched; an incompatible generic
 * field and a {@code final} field both fail the pre-check with zero field
 * reads or writes; and a short regression using the original
 * {@link FieldUtils} interface produces the same copied values.
 * </p>
 */
class ExplicitFieldCopierTest {

    /** First minimal generic fixture class. */
    public static class Holder<T> {
        public T item;
        public List<T> items;
        public String label;
        public String untouched;

        Holder() {
        }
    }

    /** Second minimal generic fixture class, identical field shape, plus a final field in a third fixture. */
    public static class OtherHolder<T> {
        public T item;
        public List<T> items;
        public String label;
        public String untouched;

        OtherHolder() {
        }
    }

    /** Fixture whose destination field is final. */
    public static class FinalHolder<T> {
        public final T item;

        FinalHolder(final T item) {
            this.item = item;
        }
    }

    /** Fixture with a non-public field to exercise FieldUtils access resolution. */
    public static class PrivateHolder<T> {
        T item;

        PrivateHolder() {
        }
    }

    @TempDir
    Path tempDir;

    @Test
    void testCompatibleGenericFieldsAreCopiedAndOnlyDeclaredFieldsAreTouched() throws Exception {
        final Holder<String> source = new Holder<>();
        source.item = "hello";
        source.items = Arrays.asList("a", "b");
        source.label = "source-label";
        source.untouched = "do-not-copy";

        final OtherHolder<String> target = new OtherHolder<>();

        final ExplicitFieldCopier.FieldCopyPlan plan = ExplicitFieldCopier.plan(
                TypeUtils.parameterize(Holder.class, String.class), TypeUtils.parameterize(OtherHolder.class, String.class),
                Arrays.asList("item", "items", "label"), true);
        assertTrue(plan.isValid(), () -> "expected a valid plan but got " + plan.getViolations());
        assertEquals(3, plan.getCopyCount());

        ExplicitFieldCopier.copy(source, target, plan);

        // Only the explicit declared fields are copied; untouched is never read or written.
        assertEquals("hello", target.item);
        assertEquals(Arrays.asList("a", "b"), target.items);
        assertEquals("source-label", target.label);
        assertEquals(null, target.untouched);

        writeReport("testCompatibleGenericFieldsAreCopiedAndOnlyDeclaredFieldsAreTouched",
                "plan valid=true, copyCount=" + plan.getCopyCount() + System.lineSeparator()
                        + "target.item=" + target.item + ", target.items=" + target.items
                        + ", target.label=" + target.label + ", target.untouched=" + target.untouched);
    }

    @Test
    void testIncompatibleGenericFieldFailsPrecheckWithZeroAccess() throws Exception {
        final Holder<String> source = new Holder<>();
        source.items = Arrays.asList("a", "b");

        final OtherHolder<Integer> target = new OtherHolder<>();

        // Source items is List<String>; target items is List<Integer>: pre-check must reject it.
        final ExplicitFieldCopier.FieldCopyPlan plan = ExplicitFieldCopier.plan(
                TypeUtils.parameterize(Holder.class, String.class), TypeUtils.parameterize(OtherHolder.class, Integer.class),
                Arrays.asList("items"), true);
        assertFalse(plan.isValid());
        assertEquals(1, plan.getViolations().size());
        assertTrue(plan.getViolations().get(0).contains("incompatible types"));
        assertEquals(0, plan.getCopyCount());

        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final ExplicitFieldCopier.FieldValueReader countingReader = (field, obj, force) -> {
            reads.incrementAndGet();
            return FieldUtils.readField(field, obj, force);
        };
        final ExplicitFieldCopier.FieldValueWriter countingWriter = (field, obj, value, force) -> {
            writes.incrementAndGet();
            FieldUtils.writeField(field, obj, value, force);
        };

        // Committing an invalid plan is refused before any field access happens.
        assertThrows(IllegalArgumentException.class,
                () -> ExplicitFieldCopier.copy(source, target, plan, countingReader, countingWriter));
        assertEquals(0, reads.get(), "an incompatible-field plan must not read any field");
        assertEquals(0, writes.get(), "an incompatible-field plan must not write any field");
        assertEquals(null, target.items);

        writeReport("testIncompatibleGenericFieldFailsPrecheckWithZeroAccess",
                "plan valid=false, violations=" + plan.getViolations() + System.lineSeparator()
                        + "reads=" + reads.get() + ", writes=" + writes.get() + ", target.items=" + target.items);
    }

    @Test
    void testFinalTargetFieldFailsPrecheckWithZeroAccess() throws Exception {
        final Holder<String> source = new Holder<>();
        source.item = "hello";
        final FinalHolder<String> target = new FinalHolder<>("original");

        final ExplicitFieldCopier.FieldCopyPlan plan = ExplicitFieldCopier.plan(
                TypeUtils.parameterize(Holder.class, String.class), TypeUtils.parameterize(FinalHolder.class, String.class),
                Arrays.asList("item"), true);
        assertFalse(plan.isValid());
        assertEquals(1, plan.getViolations().size());
        assertTrue(plan.getViolations().get(0).contains("final"));
        assertEquals(0, plan.getCopyCount());

        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final ExplicitFieldCopier.FieldValueReader countingReader = (field, obj, force) -> {
            reads.incrementAndGet();
            return FieldUtils.readField(field, obj, force);
        };
        final ExplicitFieldCopier.FieldValueWriter countingWriter = (field, obj, value, force) -> {
            writes.incrementAndGet();
            FieldUtils.writeField(field, obj, value, force);
        };

        assertThrows(IllegalArgumentException.class,
                () -> ExplicitFieldCopier.copy(source, target, plan, countingReader, countingWriter));
        assertEquals(0, reads.get(), "a final-field plan must not read any field");
        assertEquals(0, writes.get(), "a final-field plan must not write any field");
        // The final field keeps its constructor value: nothing was written.
        assertEquals("original", target.item);

        writeReport("testFinalTargetFieldFailsPrecheckWithZeroAccess",
                "plan valid=false, violations=" + plan.getViolations() + System.lineSeparator()
                        + "reads=" + reads.get() + ", writes=" + writes.get() + ", target.item=" + target.item);
    }

    @Test
    void testInaccessibleFieldFailsPrecheckWithoutForceAccess() throws Exception {
        // PrivateHolder.item is package-private: without forceAccess FieldUtils cannot resolve it.
        final ExplicitFieldCopier.FieldCopyPlan plan = ExplicitFieldCopier.plan(
                TypeUtils.parameterize(PrivateHolder.class, String.class), TypeUtils.parameterize(OtherHolder.class, String.class),
                Arrays.asList("item"), false);
        assertFalse(plan.isValid());
        assertTrue(plan.getViolations().get(0).contains("Cannot locate accessible source field"));
        assertEquals(0, plan.getCopyCount());
    }

    /**
     * Short regression: the copier must land exactly the same values as the
     * original {@link FieldUtils} read/write interface used field by field.
     */
    @Test
    void testCopiedValuesMatchOriginalFieldUtilsInterface() throws Exception {
        final Holder<String> source = new Holder<>();
        source.item = "regression";
        source.items = Arrays.asList("x", "y");
        source.label = "label";

        final OtherHolder<String> copierTarget = new OtherHolder<>();
        final OtherHolder<String> directTarget = new OtherHolder<>();

        final List<String> names = Arrays.asList("item", "items", "label");
        final ExplicitFieldCopier.FieldCopyPlan plan = ExplicitFieldCopier.plan(
                TypeUtils.parameterize(Holder.class, String.class), TypeUtils.parameterize(OtherHolder.class, String.class),
                names, true);
        assertTrue(plan.isValid());
        ExplicitFieldCopier.copy(source, copierTarget, plan);

        // Original reflection interface, one declared field at a time.
        for (final String name : names) {
            final Field sourceField = FieldUtils.getDeclaredField(Holder.class, name, true);
            final Field targetField = FieldUtils.getDeclaredField(OtherHolder.class, name, true);
            FieldUtils.writeField(targetField, directTarget, FieldUtils.readField(sourceField, source), true);
        }

        assertEquals(directTarget.item, copierTarget.item);
        assertEquals(directTarget.items, copierTarget.items);
        assertEquals(directTarget.label, copierTarget.label);

        writeReport("testCopiedValuesMatchOriginalFieldUtilsInterface",
                "copierTarget.item=" + copierTarget.item + ", directTarget.item=" + directTarget.item
                        + System.lineSeparator() + "copierTarget.items=" + copierTarget.items
                        + ", directTarget.items=" + directTarget.items + System.lineSeparator()
                        + "copierTarget.label=" + copierTarget.label + ", directTarget.label=" + directTarget.label);
    }

    private void writeReport(final String name, final String body) throws IOException {
        final Path report = tempDir.resolve(name + ".txt");
        final String content = "test=" + name + System.lineSeparator() + body + System.lineSeparator();
        Files.write(report, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.size(report) > 0);
        System.out.print(System.lineSeparator() + "----- fixture report: " + report + " -----" + System.lineSeparator() + content);
    }
}
