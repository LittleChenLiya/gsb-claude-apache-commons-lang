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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.Validate;

/**
 * Precheck-and-commit helper that copies only an explicitly named set of fields from one object to another.
 *
 * <p>
 * This is intentionally <em>not</em> a generic object mapper: nothing is inferred from the object graphs and no
 * field is ever copied unless its name was listed explicitly by the caller. The copy runs in two phases:
 * </p>
 * <ol>
 * <li><b>Precheck</b> ({@link #precheck(Class, Class, String...)}): for every requested name the source and target
 * {@link Field} handles are resolved through {@link FieldUtils#getField(Class, String, boolean)} with forced
 * access, the target field is required to be non-final, and the generic type of the source field, resolved against
 * the runtime class through {@link TypeUtils#getTypeArguments(Type, Class)} and
 * {@link TypeUtils#unrollVariables(Map, Type)}, must be assignable to the generic type of the target field via
 * {@link TypeUtils#isAssignable(Type, Type)}. No instance is read or written during this phase.</li>
 * <li><b>Commit</b> ({@link #copyDeclaredFields(Object, Object, String...)}): only if the precheck reports no
 * violation, each listed field is read with {@link FieldUtils#readField(Field, Object)} and written with
 * {@link FieldUtils#writeField(Field, Object, Object)}, the original reflection interfaces. If any precheck fails,
 * zero fields are read and zero fields are written.</li>
 * </ol>
 *
 * @since 3.20.0
 */
public final class FieldCopyUtils {

    /**
     * Result of the type and access precheck for an explicit field set. Pure metadata: no object instance is
     * touched while building it.
     */
    public static final class Precheck {

        private final List<String> fieldNames;
        private final List<Field> sourceFields;
        private final List<Field> targetFields;
        private final List<String> violations;

        private Precheck(final List<String> fieldNames, final List<Field> sourceFields,
                final List<Field> targetFields, final List<String> violations) {
            this.fieldNames = fieldNames;
            this.sourceFields = sourceFields;
            this.targetFields = targetFields;
            this.violations = violations;
        }

        /**
         * Gets the explicitly requested field names, de-duplicated and in request order.
         *
         * @return the requested field names.
         */
        public List<String> getFieldNames() {
            return fieldNames;
        }

        /**
         * Gets the violations found during the precheck; empty means the copy may be committed.
         *
         * @return the violations, never null.
         */
        public List<String> getViolations() {
            return violations;
        }

        /**
         * Tests whether every requested field passed the access, final-modifier and generic-assignability checks.
         *
         * @return true if the explicit field set may be copied.
         */
        public boolean isAssignable() {
            return violations.isEmpty();
        }

        @Override
        public String toString() {
            return isAssignable() ? "Precheck[ok, fields=" + fieldNames + "]"
                    : "Precheck[violations=" + violations + "]";
        }
    }

    /**
     * Outcome of {@link #copyDeclaredFields(Object, Object, String...)}: the precheck plus the number of fields
     * actually read and written through {@link FieldUtils}.
     */
    public static final class CopyResult {

        private final Precheck precheck;
        private final int fieldsRead;
        private final int fieldsWritten;

        private CopyResult(final Precheck precheck, final int fieldsRead, final int fieldsWritten) {
            this.precheck = precheck;
            this.fieldsRead = fieldsRead;
            this.fieldsWritten = fieldsWritten;
        }

        /**
         * Gets the number of fields read from the source; zero when the precheck failed.
         *
         * @return the read count.
         */
        public int getFieldsRead() {
            return fieldsRead;
        }

        /**
         * Gets the number of fields written to the target; zero when the precheck failed.
         *
         * @return the write count.
         */
        public int getFieldsWritten() {
            return fieldsWritten;
        }

        /**
         * Gets the precheck that gated this copy.
         *
         * @return the precheck, never null.
         */
        public Precheck getPrecheck() {
            return precheck;
        }

        /**
         * Gets the precheck violations; empty when the copy was committed.
         *
         * @return the violations, never null.
         */
        public List<String> getViolations() {
            return precheck.getViolations();
        }

        /**
         * Tests whether the precheck passed and the explicit field set was committed through {@link FieldUtils}.
         *
         * @return true if the copy was committed.
         */
        public boolean isCommitted() {
            return precheck.isAssignable();
        }

        @Override
        public String toString() {
            return "CopyResult[committed=" + isCommitted() + ", read=" + fieldsRead + ", written=" + fieldsWritten
                    + (isCommitted() ? "" : ", violations=" + getViolations()) + "]";
        }
    }

    /**
     * Copies only the explicitly named fields from source to target.
     *
     * <p>
     * The {@link #precheck(Class, Class, String...)} runs first; if it reports any violation, this method performs
     * zero reads and zero writes and returns a {@link CopyResult} carrying the violations. Otherwise each listed
     * field is read with {@link FieldUtils#readField(Field, Object)} and written with
     * {@link FieldUtils#writeField(Field, Object, Object)}.
     * </p>
     *
     * @param source the object to read from, not null.
     * @param target the object to write to, not null.
     * @param fieldNames the explicit set of field names to copy, not null, no blank element.
     * @return the outcome, including per-phase read and write counts.
     */
    public static CopyResult copyDeclaredFields(final Object source, final Object target,
            final String... fieldNames) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        final Precheck precheck = precheck(source.getClass(), target.getClass(), fieldNames);
        if (!precheck.isAssignable()) {
            return new CopyResult(precheck, 0, 0);
        }
        int reads = 0;
        int writes = 0;
        for (int i = 0; i < precheck.sourceFields.size(); i++) {
            try {
                final Object value = FieldUtils.readField(precheck.sourceFields.get(i), source);
                reads++;
                FieldUtils.writeField(precheck.targetFields.get(i), target, value);
                writes++;
            } catch (final IllegalAccessException e) {
                // The precheck resolved both fields through FieldUtils with forced access, so this is unexpected.
                throw new IllegalStateException("Prechecked field became inaccessible: " + precheck.fieldNames.get(i),
                        e);
            }
        }
        return new CopyResult(precheck, reads, writes);
    }

    /**
     * Runs the type and access precheck for an explicit field set without reading or writing any field.
     *
     * <p>
     * For each requested name, in order:
     * </p>
     * <ul>
     * <li>the source and target fields must be resolvable through
     * {@link FieldUtils#getField(Class, String, boolean)} with forced access;</li>
     * <li>the target field must not be final;</li>
     * <li>the generic type of the source field, resolved against sourceType through
     * {@link TypeUtils#getTypeArguments(Type, Class)} and {@link TypeUtils#unrollVariables(Map, Type)}, must be
     * assignable to the resolved generic type of the target field via
     * {@link TypeUtils#isAssignable(Type, Type)}.</li>
     * </ul>
     *
     * @param sourceType the class to read from, not null.
     * @param targetType the class to write to, not null.
     * @param fieldNames the explicit set of field names, not null, no blank element.
     * @return the precheck outcome; {@link Precheck#isAssignable()} gates the commit phase.
     */
    public static Precheck precheck(final Class<?> sourceType, final Class<?> targetType,
            final String... fieldNames) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(fieldNames, "fieldNames");
        final Set<String> names = new LinkedHashSet<>();
        for (final String name : fieldNames) {
            Validate.isTrue(name != null && !name.trim().isEmpty(), "The field name must not be null or blank");
            names.add(name);
        }
        final List<String> orderedNames = new ArrayList<>(names);
        final List<Field> sourceFields = new ArrayList<>(orderedNames.size());
        final List<Field> targetFields = new ArrayList<>(orderedNames.size());
        final List<String> violations = new ArrayList<>();
        for (final String name : orderedNames) {
            final Field sourceField = FieldUtils.getField(sourceType, name, true);
            if (sourceField == null) {
                violations.add("source field not accessible via FieldUtils: " + name + " on " + sourceType.getName());
                continue;
            }
            final Field targetField = FieldUtils.getField(targetType, name, true);
            if (targetField == null) {
                violations.add("target field not accessible via FieldUtils: " + name + " on " + targetType.getName());
                continue;
            }
            if (Modifier.isFinal(targetField.getModifiers())) {
                violations.add("target field is final: " + name + " on " + targetType.getName());
                continue;
            }
            final Type sourceFieldType = resolveFieldType(sourceType, sourceField);
            final Type targetFieldType = resolveFieldType(targetType, targetField);
            if (!TypeUtils.isAssignable(sourceFieldType, targetFieldType)) {
                violations.add("generic type not assignable for field " + name + ": " + sourceFieldType.getTypeName()
                        + " -> " + targetFieldType.getTypeName());
                continue;
            }
            sourceFields.add(sourceField);
            targetFields.add(targetField);
        }
        return new Precheck(Collections.unmodifiableList(orderedNames), Collections.unmodifiableList(sourceFields),
                Collections.unmodifiableList(targetFields), Collections.unmodifiableList(violations));
    }

    /**
     * Resolves the generic type of a field against a runtime class, unrolling type variables through
     * {@link TypeUtils}.
     */
    private static Type resolveFieldType(final Class<?> runtimeType, final Field field) {
        final Type genericType = field.getGenericType();
        final Map<TypeVariable<?>, Type> typeArguments =
                TypeUtils.getTypeArguments(runtimeType, field.getDeclaringClass());
        if (typeArguments == null || typeArguments.isEmpty()) {
            return genericType;
        }
        final Type unrolled = TypeUtils.unrollVariables(typeArguments, genericType);
        return unrolled != null ? unrolled : genericType;
    }

    private FieldCopyUtils() {
        // no instances
    }
}
