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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.Validate;

/**
 * Copies an explicitly named set of fields from one object to another using
 * reflection, but only after a type pre-check has passed for every field.
 *
 * <p>
 * This is deliberately not a general purpose object mapper: callers name the
 * exact declared fields they want copied, and the pre-check resolves the
 * generic field types on both sides with {@link TypeUtils}, verifies
 * {@link FieldUtils} access rules and rejects {@code final} destination
 * fields. If any single field is illegal, no field value is read or written.
 * When the plan is valid, {@link #copy(Object, Object, FieldCopyPlan)} commits
 * the copies through the existing {@link FieldUtils} reflection interface.
 * </p>
 *
 * @since 3.21.1
 */
public final class ExplicitFieldCopier {

    /**
     * A pre-checked, immutable description of the field copies to commit.
     *
     * <p>
     * A plan is created without reading or writing any field value; it only
     * inspects reflection metadata. {@link #isValid()} must be {@code true}
     * before {@link ExplicitFieldCopier#copy(Object, Object, FieldCopyPlan)}
     * can be used.
     * </p>
     */
    public static final class FieldCopyPlan {

        private final boolean forceAccess;
        private final List<FieldCopy> copies;
        private final Class<?> sourceClass;
        private final Class<?> targetClass;
        private final List<String> violations;

        private FieldCopyPlan(final Class<?> sourceClass, final Class<?> targetClass, final boolean forceAccess,
                final List<FieldCopy> copies, final List<String> violations) {
            this.sourceClass = sourceClass;
            this.targetClass = targetClass;
            this.forceAccess = forceAccess;
            this.copies = Collections.unmodifiableList(copies);
            this.violations = Collections.unmodifiableList(violations);
        }

        /**
         * Gets the number of fields that will be copied when the plan is committed.
         *
         * @return the number of valid field copies; {@code 0} when {@link #isValid()} is {@code false}.
         */
        public int getCopyCount() {
            return copies.size();
        }

        /**
         * Gets the ordered names of the declared fields this plan copies.
         *
         * @return an unmodifiable list of the field names in request order.
         */
        public List<String> getFieldNames() {
            final List<String> names = new ArrayList<>(copies.size());
            copies.forEach(copy -> names.add(copy.sourceField.getName()));
            return Collections.unmodifiableList(names);
        }

        /**
         * Gets the raw source type the plan was resolved against.
         *
         * @return the source class.
         */
        public Class<?> getSourceClass() {
            return sourceClass;
        }

        /**
         * Gets the raw target type the plan was resolved against.
         *
         * @return the target class.
         */
        public Class<?> getTargetClass() {
            return targetClass;
        }

        /**
         * Gets all pre-check violations collected for the requested field set.
         *
         * @return an unmodifiable list of violation descriptions; empty when {@link #isValid()} is {@code true}.
         */
        public List<String> getViolations() {
            return violations;
        }

        /**
         * Tests whether every requested field passed the pre-check.
         *
         * @return {@code true} if the plan can be committed, {@code false} otherwise.
         */
        public boolean isValid() {
            return violations.isEmpty();
        }

        boolean isForceAccess() {
            return forceAccess;
        }

        List<FieldCopy> getCopies() {
            return copies;
        }
    }

    /** A matched pair of source and destination fields. */
    private static final class FieldCopy {

        private final Field sourceField;
        private final Field targetField;

        private FieldCopy(final Field sourceField, final Field targetField) {
            this.sourceField = sourceField;
            this.targetField = targetField;
        }
    }

    /**
     * Reads a field value through the underlying reflection interface.
     *
     * <p>
     * Package-private seam so tests can observe that an invalid plan performs
     * zero field accesses; the default implementation delegates to
     * {@link FieldUtils#readField(Field, Object, boolean)}.
     * </p>
     */
    @FunctionalInterface
    interface FieldValueReader {
        Object read(Field field, Object target, boolean forceAccess) throws IllegalAccessException;
    }

    /**
     * Writes a field value through the underlying reflection interface.
     *
     * <p>
     * Package-private seam so tests can observe that an invalid plan performs
     * zero field accesses; the default implementation delegates to
     * {@link FieldUtils#writeField(Field, Object, Object, boolean)}.
     * </p>
     */
    @FunctionalInterface
    interface FieldValueWriter {
        void write(Field field, Object target, Object value, boolean forceAccess) throws IllegalAccessException;
    }

    /** Default reader committing through the existing {@link FieldUtils} reflection interface. */
    private static final FieldValueReader DEFAULT_READER = FieldUtils::readField;

    /** Default writer committing through the existing {@link FieldUtils} reflection interface. */
    private static final FieldValueWriter DEFAULT_WRITER = FieldUtils::writeField;

    /**
     * Builds a pre-checked plan for copying the named declared fields, respecting field scope.
     *
     * @param sourceType the generic source type, for example created with
     *        {@link TypeUtils#parameterize(Class, Type...)}.
     * @param targetType the generic target type.
     * @param fieldNames the explicit, non-blank, duplicate-free declared field names to copy.
     * @return an immutable plan; inspect {@link FieldCopyPlan#isValid()} and
     *         {@link FieldCopyPlan#getViolations()} before committing.
     * @throws NullPointerException if a type or field-name collection is {@code null}.
     * @throws IllegalArgumentException if a field name is blank or duplicated.
     */
    public static FieldCopyPlan plan(final Type sourceType, final Type targetType, final Collection<String> fieldNames) {
        return plan(sourceType, targetType, fieldNames, false);
    }

    /**
     * Builds a pre-checked plan for copying the named declared fields.
     *
     * <p>
     * Every requested field must satisfy all of the following, otherwise a
     * single invalid plan containing every collected violation is returned and
     * no field value is read or written:
     * </p>
     * <ul>
     * <li>the field is declared on both the source and target classes and is
     * reachable under {@link FieldUtils#getDeclaredField(Class, String, boolean)}
     * access rules ({@code public} when {@code forceAccess} is {@code false});</li>
     * <li>the destination field is not {@code final}, because
     * {@link FieldUtils#writeField(Field, Object, Object, boolean)} refuses final fields;</li>
     * <li>the generic field type on the source side is assignable to the
     * destination side according to {@link TypeUtils#isAssignable(Type, Type)}.</li>
     * </ul>
     *
     * @param sourceType the generic source type, for example created with
     *        {@link TypeUtils#parameterize(Class, Type...)}.
     * @param targetType the generic target type.
     * @param fieldNames the explicit, non-blank, duplicate-free declared field names to copy.
     * @param forceAccess whether scope restrictions may be broken when resolving fields.
     * @return an immutable plan; inspect {@link FieldCopyPlan#isValid()} and
     *         {@link FieldCopyPlan#getViolations()} before committing.
     * @throws NullPointerException if a type or field-name collection is {@code null}.
     * @throws IllegalArgumentException if a field name is blank or duplicated.
     */
    public static FieldCopyPlan plan(final Type sourceType, final Type targetType, final Collection<String> fieldNames,
            final boolean forceAccess) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(fieldNames, "fieldNames");
        final Class<?> sourceClass = TypeUtils.getRawType(sourceType, null);
        final Class<?> targetClass = TypeUtils.getRawType(targetType, null);
        Validate.notNull(sourceClass, "Cannot resolve raw source type from %s", sourceType);
        Validate.notNull(targetClass, "Cannot resolve raw target type from %s", targetType);

        final List<String> normalizedNames = new ArrayList<>(fieldNames.size());
        final Set<String> uniqueNames = new LinkedHashSet<>();
        for (final String fieldName : fieldNames) {
            Validate.isTrue(fieldName != null && !fieldName.trim().isEmpty(), "The field name must not be blank/empty");
            Validate.isTrue(uniqueNames.add(fieldName), "Duplicate field name: %s", fieldName);
            normalizedNames.add(fieldName);
        }

        final Map<TypeVariable<?>, Type> sourceArguments = TypeUtils.getTypeArguments(sourceType, sourceClass);
        final Map<TypeVariable<?>, Type> targetArguments = TypeUtils.getTypeArguments(targetType, targetClass);

        final List<FieldCopy> copies = new ArrayList<>(normalizedNames.size());
        final List<String> violations = new ArrayList<>();
        for (final String fieldName : normalizedNames) {
            final Field sourceField = FieldUtils.getDeclaredField(sourceClass, fieldName, forceAccess);
            final Field targetField = FieldUtils.getDeclaredField(targetClass, fieldName, forceAccess);
            boolean fieldLegal = true;
            if (sourceField == null) {
                violations.add(String.format("Cannot locate accessible source field %s.%s", sourceClass.getName(), fieldName));
                fieldLegal = false;
            }
            if (targetField == null) {
                violations.add(String.format("Cannot locate accessible target field %s.%s", targetClass.getName(), fieldName));
                fieldLegal = false;
            }
            if (targetField != null && Modifier.isFinal(targetField.getModifiers())) {
                violations.add(String.format("Cannot copy to final target field %s.%s", targetClass.getName(), fieldName));
                fieldLegal = false;
            }
            if (sourceField != null && targetField != null && !Modifier.isFinal(targetField.getModifiers())) {
                final Type sourceFieldType = resolveFieldType(sourceField, sourceArguments);
                final Type targetFieldType = resolveFieldType(targetField, targetArguments);
                if (!TypeUtils.isAssignable(sourceFieldType, targetFieldType)) {
                    violations.add(String.format("Field %s has incompatible types: %s cannot be assigned to %s", fieldName,
                            TypeUtils.toString(sourceFieldType), TypeUtils.toString(targetFieldType)));
                    fieldLegal = false;
                }
            }
            if (fieldLegal) {
                copies.add(new FieldCopy(sourceField, targetField));
            }
        }
        return new FieldCopyPlan(sourceClass, targetClass, forceAccess, copies, violations);
    }

    /**
     * Commits a valid plan by reading each named source field and writing it to
     * the matching target field, field by field, through {@link FieldUtils}.
     *
     * @param source the object to read declared fields from.
     * @param target the object to write declared fields to.
     * @param plan a valid plan produced by {@link #plan(Type, Type, Collection, boolean)}.
     * @throws NullPointerException if an argument is {@code null}.
     * @throws IllegalArgumentException if the plan is invalid or the objects do
     *         not match the types the plan was built for.
     * @throws IllegalAccessException if the underlying reflection access fails.
     */
    public static void copy(final Object source, final Object target, final FieldCopyPlan plan) throws IllegalAccessException {
        copy(source, target, plan, DEFAULT_READER, DEFAULT_WRITER);
    }

    /**
     * Commits a valid plan using explicit read/write seams.
     *
     * @param source the object to read declared fields from.
     * @param target the object to write declared fields to.
     * @param plan a valid plan.
     * @param reader the reflection read operation.
     * @param writer the reflection write operation.
     * @throws IllegalArgumentException if the plan is invalid or types do not match.
     * @throws IllegalAccessException if reflection access fails.
     */
    static void copy(final Object source, final Object target, final FieldCopyPlan plan, final FieldValueReader reader,
            final FieldValueWriter writer) throws IllegalAccessException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(writer, "writer");
        Validate.isTrue(plan.isValid(), "Cannot commit an invalid field copy plan: %s", plan.getViolations());
        Validate.isTrue(plan.getSourceClass().isInstance(source), "Source %s is not a %s", source.getClass(), plan.getSourceClass());
        Validate.isTrue(plan.getTargetClass().isInstance(target), "Target %s is not a %s", target.getClass(), plan.getTargetClass());
        for (final FieldCopy fieldCopy : plan.getCopies()) {
            final Object value = reader.read(fieldCopy.sourceField, source, plan.isForceAccess());
            writer.write(fieldCopy.targetField, target, value, plan.isForceAccess());
        }
    }

    /**
     * Resolves a field's generic type against the type arguments of its owning type.
     *
     * @param field the declared field.
     * @param typeArguments the type variable assignments of the owning type.
     * @return the resolved field type, or the raw generic type if it cannot be fully resolved.
     */
    private static Type resolveFieldType(final Field field, final Map<TypeVariable<?>, Type> typeArguments) {
        final Type fieldType = field.getGenericType();
        final Type unrolled = TypeUtils.unrollVariables(typeArguments, fieldType);
        return unrolled != null ? unrolled : fieldType;
    }

    private ExplicitFieldCopier() {
        // no instances
    }
}
