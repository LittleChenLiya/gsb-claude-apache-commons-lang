/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.lang3;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Computes Levenshtein edit distances between character sequences and reports the outcome as an explicit
 * {@link LevenshteinReceipt} instead of overloading sentinel integers.
 *
 * <p>
 * A receipt distinguishes three outcomes:
 * </p>
 * <ul>
 * <li>{@link Status#EXACT} &ndash; the exact distance was computed and is less than or equal to the threshold;</li>
 * <li>{@link Status#EXCEEDED_THRESHOLD} &ndash; the distance is provably greater than the threshold; no concrete
 * distance is fabricated;</li>
 * <li>{@link Status#NOT_COMPUTED} &ndash; the distance was not computed at all (a {@code null} input or a negative
 * threshold).</li>
 * </ul>
 *
 * <p>
 * Distances are measured in a declared {@link Unit}: Unicode code points ({@link Unit#CODE_POINT}) or UTF-16 code
 * units ({@link Unit#UTF_16}). The two can disagree: the emoji {@code U+1F600} is one code point but two UTF-16
 * code units, so its distance to {@code "a"} is 1 in code points and 2 in UTF-16 units.
 * </p>
 *
 * <p>
 * Four-string example (code-point unit, unbounded):
 * </p>
 * <pre>
 * levenshtein("frog", "fog") = EXACT 1
 * levenshtein("frog", "fly") = EXACT 3
 * levenshtein("frog", "ant") = EXACT 4
 * levenshtein("fly",  "ant") = EXACT 3
 * </pre>
 *
 * <p>
 * The computation short-circuits only when the threshold is provably exceeded (the length difference alone exceeds
 * the threshold, or the diagonal stripe of the cost matrix provably runs off the table). Input sequences are never
 * modified; they are copied into internal unit arrays before the dynamic-programming pass.
 * </p>
 *
 * <p>
 * The classic {@link StringUtils#getLevenshteinDistance(CharSequence, CharSequence)} family is untouched by this
 * class and remains available.
 * </p>
 *
 * @since 3.21.1
 */
public final class StringDistance {

    /**
     * The outcome of a distance request.
     */
    public enum Status {
        /** The exact distance was computed; it is less than or equal to the requested threshold. */
        EXACT,
        /** The distance is provably greater than the requested threshold; no exact value is reported. */
        EXCEEDED_THRESHOLD,
        /** The distance was not computed (a {@code null} input or a negative threshold). */
        NOT_COMPUTED
    }

    /**
     * The unit in which a distance is measured.
     */
    public enum Unit {
        /** One unit per Unicode code point; supplementary characters (emoji, ...) count as one. */
        CODE_POINT,
        /** One unit per UTF-16 code unit (Java {@code char}); supplementary characters count as two. */
        UTF_16
    }

    /**
     * An explicit receipt for a Levenshtein distance request.
     *
     * <p>
     * The receipt always carries the {@link Unit} that was used and a <em>verified bound</em>:
     * </p>
     * <ul>
     * <li>{@link Status#EXACT}: the verified bound equals the exact distance;</li>
     * <li>{@link Status#EXCEEDED_THRESHOLD}: the verified bound equals the threshold, meaning the distance was
     * verified to be strictly greater than it;</li>
     * <li>{@link Status#NOT_COMPUTED}: the verified bound is {@code -1}, meaning nothing was verified.</li>
     * </ul>
     */
    public static final class LevenshteinReceipt {

        private static LevenshteinReceipt exact(final int distance, final int threshold, final Unit unit) {
            return new LevenshteinReceipt(Status.EXACT, unit, threshold, distance, distance);
        }

        private static LevenshteinReceipt exceeded(final int threshold, final Unit unit) {
            return new LevenshteinReceipt(Status.EXCEEDED_THRESHOLD, unit, threshold, -1, threshold);
        }

        private static LevenshteinReceipt notComputed(final int threshold, final Unit unit) {
            return new LevenshteinReceipt(Status.NOT_COMPUTED, unit, threshold, -1, -1);
        }

        private final Status status;
        private final Unit unit;
        private final int threshold;
        private final int distance;
        private final int verifiedBound;

        private LevenshteinReceipt(final Status status, final Unit unit, final int threshold, final int distance,
                final int verifiedBound) {
            this.status = status;
            this.unit = unit;
            this.threshold = threshold;
            this.distance = distance;
            this.verifiedBound = verifiedBound;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LevenshteinReceipt)) {
                return false;
            }
            final LevenshteinReceipt other = (LevenshteinReceipt) obj;
            return status == other.status && unit == other.unit && threshold == other.threshold
                    && distance == other.distance && verifiedBound == other.verifiedBound;
        }

        /**
         * Gets the exact distance. Present only when {@link #getStatus()} is {@link Status#EXACT}; empty for
         * {@link Status#EXCEEDED_THRESHOLD} (no distance is fabricated) and {@link Status#NOT_COMPUTED}.
         *
         * @return the exact distance, or empty.
         */
        public OptionalInt getDistance() {
            return status == Status.EXACT ? OptionalInt.of(distance) : OptionalInt.empty();
        }

        /**
         * Gets the outcome status.
         *
         * @return the status, never {@code null}.
         */
        public Status getStatus() {
            return status;
        }

        /**
         * Gets the requested threshold, or {@link Integer#MAX_VALUE} for an unbounded request.
         *
         * @return the threshold.
         */
        public int getThreshold() {
            return threshold;
        }

        /**
         * Gets the unit in which the distance was (or would have been) measured.
         *
         * @return the unit, never {@code null}.
         */
        public Unit getUnit() {
            return unit;
        }

        /**
         * Gets the verified bound: the exact distance when {@link Status#EXACT}, the threshold when
         * {@link Status#EXCEEDED_THRESHOLD} (the distance is verified to exceed it), or {@code -1} when
         * {@link Status#NOT_COMPUTED}.
         *
         * @return the verified bound.
         */
        public int getVerifiedBound() {
            return verifiedBound;
        }

        @Override
        public int hashCode() {
            int result = status.hashCode();
            result = 31 * result + unit.hashCode();
            result = 31 * result + threshold;
            result = 31 * result + distance;
            result = 31 * result + verifiedBound;
            return result;
        }

        /**
         * Tests whether the exact distance was computed.
         *
         * @return {@code true} iff the status is {@link Status#EXACT}.
         */
        public boolean isExact() {
            return status == Status.EXACT;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("LevenshteinReceipt[status=").append(status)
                    .append(", unit=").append(unit).append(", threshold=").append(threshold)
                    .append(", verifiedBound=").append(verifiedBound);
            if (status == Status.EXACT) {
                builder.append(", distance=").append(distance);
            }
            return builder.append(']').toString();
        }
    }

    /**
     * Computes the unbounded Levenshtein distance between two character sequences in the given unit.
     *
     * @param left  the first input, may be {@code null} (yields {@link Status#NOT_COMPUTED}).
     * @param right the second input, may be {@code null} (yields {@link Status#NOT_COMPUTED}).
     * @param unit  the unit to measure in, must not be {@code null}.
     * @return a receipt, never {@code null}.
     * @throws NullPointerException if {@code unit} is {@code null}.
     */
    public static LevenshteinReceipt levenshtein(final CharSequence left, final CharSequence right, final Unit unit) {
        return levenshtein(left, right, Integer.MAX_VALUE, unit);
    }

    /**
     * Computes the Levenshtein distance between two character sequences in the given unit, bounded by a threshold.
     *
     * <p>
     * The returned receipt reports {@link Status#EXACT} with the accurate distance when it is less than or equal to
     * {@code threshold} (a threshold exactly equal to the distance still yields the exact value),
     * {@link Status#EXCEEDED_THRESHOLD} when the distance is provably greater (no concrete distance is fabricated),
     * and {@link Status#NOT_COMPUTED} when either input is {@code null} or the threshold is negative. An empty
     * string is a regular input and is computed normally, distinct from {@code null}.
     * </p>
     *
     * @param left      the first input, may be {@code null}.
     * @param right     the second input, may be {@code null}.
     * @param threshold the maximum distance to compute exactly, must not be negative.
     * @param unit      the unit to measure in, must not be {@code null}.
     * @return a receipt, never {@code null}.
     * @throws NullPointerException if {@code unit} is {@code null}.
     */
    public static LevenshteinReceipt levenshtein(final CharSequence left, final CharSequence right, final int threshold,
            final Unit unit) {
        Objects.requireNonNull(unit, "unit");
        if (left == null || right == null || threshold < 0) {
            return LevenshteinReceipt.notComputed(threshold, unit);
        }
        return limitedLevenshtein(toUnits(left, unit), toUnits(right, unit), threshold, unit);
    }

    /**
     * Threshold-bounded Levenshtein over unit arrays, following the diagonal-stripe algorithm of Gusfield
     * (Algorithms on Strings, Trees and Sequences) as also used by
     * {@link StringUtils#getLevenshteinDistance(CharSequence, CharSequence, int)}. Stops early only when the
     * threshold is provably exceeded.
     */
    private static LevenshteinReceipt limitedLevenshtein(int[] left, int[] right, final int threshold, final Unit unit) {
        int n = left.length;
        int m = right.length;

        // If one sequence is empty, the edit distance is necessarily the length of the other.
        if (n == 0) {
            return m <= threshold ? LevenshteinReceipt.exact(m, threshold, unit) : LevenshteinReceipt.exceeded(threshold, unit);
        }
        if (m == 0) {
            return n <= threshold ? LevenshteinReceipt.exact(n, threshold, unit) : LevenshteinReceipt.exceeded(threshold, unit);
        }
        if (Math.abs(n - m) > threshold) {
            // Provable from the length difference alone: each edit changes the length by at most one.
            return LevenshteinReceipt.exceeded(threshold, unit);
        }

        if (n > m) {
            // Swap the local copies to consume less memory; the caller inputs are never touched.
            final int[] tmp = left;
            left = right;
            right = tmp;
            n = m;
            m = right.length;
        }

        int[] previous = new int[n + 1]; // cost array of the previous row
        int[] current = new int[n + 1]; // cost array of the current row

        final int boundary = Math.min(n, threshold) + 1;
        for (int i = 0; i < boundary; i++) {
            previous[i] = i;
        }
        // These fills ensure entries outside the stripe are ignored.
        Arrays.fill(previous, boundary, previous.length, Integer.MAX_VALUE);
        Arrays.fill(current, Integer.MAX_VALUE);

        for (int j = 1; j <= m; j++) {
            final int rightUnit = right[j - 1];
            current[0] = j;

            // Compute stripe indices, constrained to the array size.
            final int min = Math.max(1, j - threshold);
            final int max = j > Integer.MAX_VALUE - threshold ? n : Math.min(n, j + threshold);

            if (min > max) {
                // The stripe provably ran off the cost matrix: the distance exceeds the threshold.
                return LevenshteinReceipt.exceeded(threshold, unit);
            }

            // Ignore the entry left of the leftmost stripe member.
            if (min > 1) {
                current[min - 1] = Integer.MAX_VALUE;
            }

            for (int i = min; i <= max; i++) {
                if (left[i - 1] == rightUnit) {
                    current[i] = previous[i - 1];
                } else {
                    current[i] = 1 + Math.min(Math.min(current[i - 1], previous[i]), previous[i - 1]);
                }
            }

            final int[] swap = current;
            current = previous;
            previous = swap;
        }

        return previous[n] <= threshold ? LevenshteinReceipt.exact(previous[n], threshold, unit)
                : LevenshteinReceipt.exceeded(threshold, unit);
    }

    /**
     * Copies a character sequence into an array of distance units without modifying the source.
     */
    private static int[] toUnits(final CharSequence input, final Unit unit) {
        if (unit == Unit.CODE_POINT) {
            return input.toString().codePoints().toArray();
        }
        final int length = input.length();
        final int[] units = new int[length];
        for (int i = 0; i < length; i++) {
            units[i] = input.charAt(i);
        }
        return units;
    }

    private StringDistance() {
        // no instances
    }
}
