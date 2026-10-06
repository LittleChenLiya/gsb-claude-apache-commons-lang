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
 * Computes the Levenshtein edit distance between two character sequences and reports the outcome as an explicit
 * {@link Receipt} instead of overloading sentinel values such as {@code -1}.
 *
 * <p>
 * Unlike {@link StringUtils#getLevenshteinDistance(CharSequence, CharSequence, int)}, which returns {@code -1} when
 * a threshold is exceeded and so cannot tell "over threshold" apart from an ordinary value, this API returns a
 * {@link Receipt} that explicitly distinguishes three states:
 * </p>
 * <ul>
 * <li>{@link Status#EXACT} - the distance was computed in full and is available from {@link Receipt#getDistance()}.</li>
 * <li>{@link Status#EXCEEDS_THRESHOLD} - the distance is provably greater than the threshold; no concrete distance
 * is fabricated.</li>
 * <li>{@link Status#NOT_COMPUTED} - the distance was not computed, for example because an input was {@code null}.</li>
 * </ul>
 *
 * <p>
 * The distance is measured in a declared {@link Unit}: either UTF-16 code units (Java {@code char}) or Unicode code
 * points. The two can differ for astral characters such as emoji: the distance between U+1F600 (GRINNING FACE) and
 * U+1FAE0 (MELTING FACE) is {@code 1} in {@link Unit#CODE_POINT} but {@code 2} in
 * {@link Unit#UTF_16}, because each emoji is a surrogate pair of two UTF-16 code units and both code units differ.
 * </p>
 *
 * <p>
 * The computation short-circuits only when it can prove the distance exceeds the threshold (for example when the
 * length difference alone is greater than the threshold, or when the diagonal stripe of the cost matrix provably
 * runs off the table). When the threshold equals the exact distance, the exact value is always reported. The input
 * sequences are only read, never modified. Every receipt carries the adopted {@link Unit} and the verified
 * (non-negative) threshold boundary.
 * </p>
 *
 * <p>
 * {@code null} inputs are kept separate from empty inputs: a {@code null} input yields a
 * {@link Status#NOT_COMPUTED} receipt, while an empty input is a genuine value and produces a normal receipt (the
 * distance to an empty sequence is the length of the other sequence). A negative threshold is rejected with
 * {@link IllegalArgumentException} rather than being treated as a request for equality.
 * </p>
 *
 * <pre>
 * LevenshteinDistance.distance("frog", "fog", 1, Unit.UTF_16).getDistance()       = 1   (EXACT)
 * LevenshteinDistance.distance("kitten", "sitting", 3, Unit.UTF_16).getDistance() = 3   (EXACT, threshold equal)
 * LevenshteinDistance.distance("kitten", "sitting", 2, Unit.UTF_16).getStatus()   = EXCEEDS_THRESHOLD
 * LevenshteinDistance.distance("😀", "🫠", 1, Unit.CODE_POINT).getDistance() = 1   (EXACT)
 * LevenshteinDistance.distance("😀", "🫠", 2, Unit.UTF_16).getDistance()     = 2   (EXACT)
 * LevenshteinDistance.distance(null, "abc", 3, Unit.UTF_16).getStatus()           = NOT_COMPUTED
 * LevenshteinDistance.distance("", "abc", 3, Unit.UTF_16).getDistance()           = 3   (EXACT)
 * LevenshteinDistance.distance(*, *, -1, *)                                       = Throws {@link IllegalArgumentException}
 * </pre>
 *
 * @since 3.21.1
 */
public final class LevenshteinDistance {

    /**
     * The outcome of a thresholded distance computation.
     */
    public enum Status {
        /** The distance was computed exactly and is at most the threshold. */
        EXACT,
        /** The distance is provably greater than the threshold; no exact value was retained. */
        EXCEEDS_THRESHOLD,
        /** The distance was not computed, for example because an input was {@code null}. */
        NOT_COMPUTED
    }

    /**
     * The character unit in which a distance is measured.
     */
    public enum Unit {
        /** UTF-16 code units, as returned by {@link CharSequence#charAt(int)}. */
        UTF_16,
        /** Unicode code points, as returned by {@link CharSequence#codePoints()}. */
        CODE_POINT
    }

    /**
     * An immutable receipt for a thresholded distance computation. Carries the {@link Status}, the adopted
     * {@link Unit}, the verified (non-negative) threshold boundary, and - only when {@link Status#EXACT} - the
     * exact distance.
     */
    public static final class Receipt {

        private final Status status;
        private final Unit unit;
        private final int threshold;
        private final int distance;

        private Receipt(final Status status, final Unit unit, final int threshold, final int distance) {
            this.status = status;
            this.unit = unit;
            this.threshold = threshold;
            this.distance = distance;
        }

        /**
         * Gets the exact distance.
         *
         * @return the exact distance, at most the threshold.
         * @throws IllegalStateException if the status is not {@link Status#EXACT}; no distance is fabricated for
         *         {@link Status#EXCEEDS_THRESHOLD} or {@link Status#NOT_COMPUTED} receipts.
         */
        public int getDistance() {
            if (status != Status.EXACT) {
                throw new IllegalStateException("No exact distance available: status is " + status);
            }
            return distance;
        }

        /**
         * Gets the exact distance if this receipt is {@link Status#EXACT}.
         *
         * @return the exact distance, or an empty optional otherwise.
         */
        public OptionalInt getDistanceIfExact() {
            return status == Status.EXACT ? OptionalInt.of(distance) : OptionalInt.empty();
        }

        /**
         * Gets the outcome of the computation.
         *
         * @return the status, never {@code null}.
         */
        public Status getStatus() {
            return status;
        }

        /**
         * Gets the verified (non-negative) threshold boundary that was applied.
         *
         * @return the threshold.
         */
        public int getThreshold() {
            return threshold;
        }

        /**
         * Gets the unit in which the distance was (or would have been) measured.
         *
         * @return the adopted unit, never {@code null}.
         */
        public Unit getUnit() {
            return unit;
        }

        /**
         * Tests whether the distance is provably greater than the threshold.
         *
         * @return whether the status is {@link Status#EXCEEDS_THRESHOLD}.
         */
        public boolean isExceedsThreshold() {
            return status == Status.EXCEEDS_THRESHOLD;
        }

        /**
         * Tests whether an exact distance is available.
         *
         * @return whether the status is {@link Status#EXACT}.
         */
        public boolean isExact() {
            return status == Status.EXACT;
        }

        /**
         * Tests whether the distance was not computed.
         *
         * @return whether the status is {@link Status#NOT_COMPUTED}.
         */
        public boolean isNotComputed() {
            return status == Status.NOT_COMPUTED;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("LevenshteinDistance.Receipt[status=").append(status)
                .append(", unit=").append(unit).append(", threshold=").append(threshold);
            if (status == Status.EXACT) {
                builder.append(", distance=").append(distance);
            }
            return builder.append("]").toString();
        }
    }

    /**
     * Computes the exact Levenshtein distance between two sequences in UTF-16 code units.
     *
     * @param left the first sequence, may be {@code null}.
     * @param right the second sequence, may be {@code null}.
     * @return a receipt; {@link Status#NOT_COMPUTED} if either input is {@code null}, otherwise {@link Status#EXACT}.
     */
    public static Receipt distance(final CharSequence left, final CharSequence right) {
        return distance(left, right, Unit.UTF_16);
    }

    /**
     * Computes the exact Levenshtein distance between two sequences in the declared unit.
     *
     * @param left the first sequence, may be {@code null}.
     * @param right the second sequence, may be {@code null}.
     * @param unit the unit in which to measure, must not be {@code null}.
     * @return a receipt; {@link Status#NOT_COMPUTED} if either input is {@code null}, otherwise {@link Status#EXACT}.
     * @throws NullPointerException if {@code unit} is {@code null}.
     */
    public static Receipt distance(final CharSequence left, final CharSequence right, final Unit unit) {
        Objects.requireNonNull(unit, "unit");
        if (left == null || right == null) {
            return new Receipt(Status.NOT_COMPUTED, unit, 0, -1);
        }
        final int[] a = toUnits(left, unit);
        final int[] b = toUnits(right, unit);
        final int threshold = Math.max(a.length, b.length);
        return new Receipt(Status.EXACT, unit, threshold, boundedDistance(a, b, threshold));
    }

    /**
     * Computes the Levenshtein distance between two sequences in UTF-16 code units, bounded by a threshold.
     *
     * @param left the first sequence, may be {@code null}.
     * @param right the second sequence, may be {@code null}.
     * @param threshold the maximum distance of interest, must not be negative.
     * @return a receipt: {@link Status#EXACT} when the distance is at most the threshold,
     *         {@link Status#EXCEEDS_THRESHOLD} when it is provably greater, {@link Status#NOT_COMPUTED} when an
     *         input is {@code null}.
     * @throws IllegalArgumentException if {@code threshold} is negative.
     */
    public static Receipt distance(final CharSequence left, final CharSequence right, final int threshold) {
        return distance(left, right, threshold, Unit.UTF_16);
    }

    /**
     * Computes the Levenshtein distance between two sequences in the declared unit, bounded by a threshold.
     *
     * <p>
     * The computation short-circuits only when the distance is provably greater than the threshold; a threshold
     * exactly equal to the distance always yields an {@link Status#EXACT} receipt with the exact value. The input
     * sequences are only read, never modified.
     * </p>
     *
     * @param left the first sequence, may be {@code null}.
     * @param right the second sequence, may be {@code null}.
     * @param threshold the maximum distance of interest, must not be negative.
     * @param unit the unit in which to measure, must not be {@code null}.
     * @return a receipt: {@link Status#EXACT} when the distance is at most the threshold,
     *         {@link Status#EXCEEDS_THRESHOLD} when it is provably greater, {@link Status#NOT_COMPUTED} when an
     *         input is {@code null}.
     * @throws IllegalArgumentException if {@code threshold} is negative.
     * @throws NullPointerException if {@code unit} is {@code null}.
     */
    public static Receipt distance(final CharSequence left, final CharSequence right, final int threshold, final Unit unit) {
        if (threshold < 0) {
            throw new IllegalArgumentException("Threshold must not be negative: " + threshold);
        }
        Objects.requireNonNull(unit, "unit");
        if (left == null || right == null) {
            return new Receipt(Status.NOT_COMPUTED, unit, threshold, -1);
        }
        final int[] a = toUnits(left, unit);
        final int[] b = toUnits(right, unit);
        final int d = boundedDistance(a, b, threshold);
        return d >= 0 ? new Receipt(Status.EXACT, unit, threshold, d) : new Receipt(Status.EXCEEDS_THRESHOLD, unit, threshold, -1);
    }

    /**
     * Computes the bounded Levenshtein distance over unit arrays.
     *
     * @return the exact distance, or {@code -1} when it is provably greater than {@code threshold}. Uses the
     *         diagonal-stripe algorithm from Gusfield, Algorithms on Strings, Trees and Sequences, matching the
     *         approach of {@link StringUtils#getLevenshteinDistance(CharSequence, CharSequence, int)}.
     */
    private static int boundedDistance(final int[] left, final int[] right, final int threshold) {
        int n = left.length;
        int m = right.length;

        // if one sequence is empty, the distance is necessarily the length of the other
        if (n == 0) {
            return m <= threshold ? m : -1;
        }
        if (m == 0) {
            return n <= threshold ? n : -1;
        }
        if (Math.abs(n - m) > threshold) {
            // provably over: the length difference alone exceeds the threshold
            return -1;
        }

        int[] s = left;
        int[] t = right;
        if (n > m) {
            // swap to consume less memory; the distance is symmetric
            s = right;
            t = left;
            n = m;
            m = t.length;
        }

        int[] p = new int[n + 1]; // previous cost array, horizontally
        int[] d = new int[n + 1]; // cost array, horizontally
        int[] tmp; // placeholder to assist in swapping p and d

        // fill in starting table values
        final int boundary = Math.min(n, threshold) + 1;
        for (int i = 0; i < boundary; i++) {
            p[i] = i;
        }
        // these fills ensure that the value above the rightmost entry of our
        // stripe will be ignored in following loop iterations
        Arrays.fill(p, boundary, p.length, Integer.MAX_VALUE);
        Arrays.fill(d, Integer.MAX_VALUE);

        // iterates through t
        for (int j = 1; j <= m; j++) {
            final int jOfT = t[j - 1]; // jth unit of t
            d[0] = j;

            // compute stripe indices, constrain to array size
            final int min = Math.max(1, j - threshold);
            final int max = j > Integer.MAX_VALUE - threshold ? n : Math.min(n, j + threshold);

            // the stripe may lead off of the table if s and t are of different sizes:
            // the distance is then provably greater than the threshold
            if (min > max) {
                return -1;
            }

            // ignore entry left of leftmost
            if (min > 1) {
                d[min - 1] = Integer.MAX_VALUE;
            }

            // iterates through [min, max] in s
            for (int i = min; i <= max; i++) {
                if (s[i - 1] == jOfT) {
                    // diagonally left and up
                    d[i] = p[i - 1];
                } else {
                    // 1 + minimum of cell to the left, to the top, diagonally left and up
                    d[i] = 1 + Math.min(Math.min(d[i - 1], p[i]), p[i - 1]);
                }
            }

            // copy current distance counts to previous row distance counts
            tmp = p;
            p = d;
            d = tmp;
        }

        // if p[n] is greater than the threshold, there is no guarantee on it being the correct
        // distance, so report "exceeds" rather than a fabricated value
        return p[n] <= threshold ? p[n] : -1;
    }

    /**
     * Converts a sequence to an array of distance units without modifying the source.
     */
    private static int[] toUnits(final CharSequence sequence, final Unit unit) {
        if (unit == Unit.CODE_POINT) {
            return sequence.codePoints().toArray();
        }
        final int length = sequence.length();
        final int[] units = new int[length];
        for (int i = 0; i < length; i++) {
            units[i] = sequence.charAt(i);
        }
        return units;
    }

    private LevenshteinDistance() {
        // no instances
    }
}
