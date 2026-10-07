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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.reflect.FieldCopyUtils.CopyResult;
import org.apache.commons.lang3.reflect.FieldCopyUtils.Precheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FieldCopyUtils}: the precheck must reject incompatible or final fields with zero field access, and a
 * successful copy must commit only the explicitly declared field set through the original {@link FieldUtils}
 * interfaces. Also keeps a short regression example for {@link FieldUtils} itself.
 */
class FieldCopyUtilsTest {

    /** Minimal generic source fixture. */
    static class SourceBox<T> {
        List<T> payload;
        List<String> names;
        String label;
        String locked = "src-locked";
        String notListed = "secret";
    }

    /** Minimal generic target fixture: incompatible names, final locked, plus an unlisted field. */
    static class TargetBox<T> {
        List<T> payload;
        List<Integer> names;
        String label;
        final String locked = "locked";
        String untouched;
    }

    /** Concrete bindings so the generic payload type unrolls to List<String> on both sides. */
    static final class StringSource extends SourceBox<String> {
    }

    static final class StringTarget extends TargetBox<String> {
    }

    /** Same shape as StringTarget but with payload bound to Integer: generically incompatible. */
    static final class IntegerTarget extends TargetBox<Integer> {
    }

    private static StringSource newSource() {
        final StringSource source = new StringSource();
        source.payload = new ArrayList<>(Arrays.asList("a", "b"));
        source.names = new ArrayList<>(Arrays.asList("n1"));
        source.label = "L";
        return source;
    }

    @Test
    void copyDeclaredFieldsCommitsOnlyExplicitFields() {
        final StringSource source = newSource();
        final StringTarget target = new StringTarget();

        final CopyResult result = FieldCopyUtils.copyDeclaredFields(source, target, "payload", "label");

        assertTrue(result.isCommitted(), () -> "unexpected violations: " + result.getViolations());
        assertEquals(2, result.getFieldsRead());
        assertEquals(2, result.getFieldsWritten());
        assertEquals(Arrays.asList("a", "b"), target.payload);
        assertEquals("L", target.label);
        // fields outside the explicit set are never touched
        assertNull(target.names);
        assertNull(target.untouched);
        assertEquals("locked", target.locked);
        assertEquals("secret", source.notListed);
    }

    @Test
    void precheckRejectsIncompatibleGenericFieldWithZeroAccess() {
        final StringSource source = newSource();
        final StringTarget target = new StringTarget();

        final CopyResult result = FieldCopyUtils.copyDeclaredFields(source, target, "names", "label");

        assertFalse(result.isCommitted());
        assertEquals(1, result.getViolations().size());
        assertTrue(result.getViolations().get(0).contains("names"), result.getViolations().toString());
        // counted reads and writes prove the commit phase never ran: zero field access
        assertEquals(0, result.getFieldsRead());
        assertEquals(0, result.getFieldsWritten());
        // even the compatible field "label" from the same request was not copied
        assertNull(target.names);
        assertNull(target.label);
    }

    @Test
    void precheckRejectsFinalTargetFieldWithZeroAccess() {
        final StringSource source = newSource();
        final StringTarget target = new StringTarget();

        final CopyResult result = FieldCopyUtils.copyDeclaredFields(source, target, "locked");

        assertFalse(result.isCommitted());
        assertTrue(result.getViolations().get(0).contains("final"), result.getViolations().toString());
        assertEquals(0, result.getFieldsRead());
        assertEquals(0, result.getFieldsWritten());
        assertEquals("locked", target.locked);
    }

    @Test
    void precheckRejectsUnknownFieldWithZeroAccess() {
        final StringSource source = newSource();
        final StringTarget target = new StringTarget();

        final CopyResult result = FieldCopyUtils.copyDeclaredFields(source, target, "missing");

        assertFalse(result.isCommitted());
        assertEquals(0, result.getFieldsRead());
        assertEquals(0, result.getFieldsWritten());
        assertNull(target.label);
        assertNull(target.payload);
    }

    @Test
    void precheckResolvesGenericTypesThroughTypeUtils() {
        // List<T> unrolls to List<String> on both concrete subclasses: assignable
        final Precheck concrete = FieldCopyUtils.precheck(StringSource.class, StringTarget.class, "payload");
        assertTrue(concrete.isAssignable(), () -> "unexpected violations: " + concrete.getViolations());

        // List<T> unrolls to List<String> on the source but List<Integer> on the target: not assignable
        final Precheck genericMismatch = FieldCopyUtils.precheck(StringSource.class, IntegerTarget.class, "payload");
        assertFalse(genericMismatch.isAssignable());
        assertTrue(genericMismatch.getViolations().get(0).contains("payload"),
                genericMismatch.getViolations().toString());

        // List<String> is never assignable to List<Integer>
        final Precheck incompatible = FieldCopyUtils.precheck(StringSource.class, StringTarget.class, "names");
        assertFalse(incompatible.isAssignable());
        assertTrue(incompatible.getViolations().get(0).contains("java.util.List<java.lang.String>"),
                incompatible.getViolations().toString());
    }

    @Test
    void precheckValidatesArguments() {
        assertThrows(NullPointerException.class,
                () -> FieldCopyUtils.precheck(null, StringTarget.class, "label"));
        assertThrows(NullPointerException.class,
                () -> FieldCopyUtils.precheck(StringSource.class, null, "label"));
        assertThrows(IllegalArgumentException.class,
                () -> FieldCopyUtils.precheck(StringSource.class, StringTarget.class, " "));
        assertThrows(NullPointerException.class,
                () -> FieldCopyUtils.copyDeclaredFields(null, new StringTarget(), "label"));
        assertThrows(NullPointerException.class,
                () -> FieldCopyUtils.copyDeclaredFields(newSource(), null, "label"));
    }

    @Test
    void copyReportCanBeWrittenToTempFileFixture(@TempDir final Path tempDir) throws IOException {
        final StringSource source = newSource();
        final StringTarget target = new StringTarget();

        final CopyResult ok = FieldCopyUtils.copyDeclaredFields(source, target, "payload", "label");
        final CopyResult rejected = FieldCopyUtils.copyDeclaredFields(source, new StringTarget(), "names");

        final Path report = tempDir.resolve("copy-report.txt");
        Files.write(report, Arrays.asList(ok.toString(), rejected.toString()), StandardCharsets.UTF_8);

        final List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        assertEquals("CopyResult[committed=true, read=2, written=2]", lines.get(0));
        assertTrue(lines.get(1).startsWith("CopyResult[committed=false, read=0, written=0"), lines.get(1));
    }

    /**
     * Short regression example for the original reflection interfaces: {@link FieldUtils} read/write still behave as
     * before, and the helper commit produces exactly the same state as a direct {@link FieldUtils} round trip.
     */
    @Test
    void fieldUtilsRegressionMatchesHelperCommit() throws IllegalAccessException {
        // original interface, direct round trip
        final StringTarget direct = new StringTarget();
        FieldUtils.writeField(direct, "label", "L", true);
        assertEquals("L", FieldUtils.readField(direct, "label", true));

        // helper commit over the same explicit field
        final StringSource source = newSource();
        final StringTarget viaHelper = new StringTarget();
        final CopyResult result = FieldCopyUtils.copyDeclaredFields(source, viaHelper, "label");
        assertTrue(result.isCommitted());

        // both paths agree
        assertEquals(FieldUtils.readField(direct, "label", true), FieldUtils.readField(viaHelper, "label", true));
        assertEquals("L", viaHelper.label);
    }
}
