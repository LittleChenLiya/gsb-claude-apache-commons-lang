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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.StringDistance.LevenshteinReceipt;
import org.apache.commons.lang3.StringDistance.Status;
import org.apache.commons.lang3.StringDistance.Unit;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link StringDistance}.
 *
 * <p>
 * Run only this test class with:
 * </p>
 *
 * <pre>
 * mvn -Dmaven.repo.local=D:/Codex/maven-repo -Dtest=StringDistanceTest -DfailIfNoTests=false \
 *     -Dcheckstyle.skip -Dpmd.skip -Dspotbugs.skip test
 * </pre>
 */
class StringDistanceTest {

    /** U+1F600 GRINNING FACE: one code point, two UTF-16 code units. */
    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void emojiDistanceDiffersBetweenUnits() {
        final LevenshteinReceipt codePoints = StringDistance.levenshtein(EMOJI, "a", Unit.CODE_POINT);
        assertEquals(Status.EXACT, codePoints.getStatus());
        assertEquals(1, codePoints.getDistance().getAsInt());
        assertEquals(Unit.CODE_POINT, codePoints.getUnit());
        assertEquals(1, codePoints.getVerifiedBound());

        final LevenshteinReceipt utf16 = StringDistance.levenshtein(EMOJI, "a", Unit.UTF_16);
        assertEquals(Status.EXACT, utf16.getStatus());
        assertEquals(2, utf16.getDistance().getAsInt());
        assertEquals(Unit.UTF_16, utf16.getUnit());
        assertEquals(2, utf16.getVerifiedBound());

        // Same strings, same threshold, different declared units: different accurate distances.
        assertTrue(codePoints.getDistance().getAsInt() != utf16.getDistance().getAsInt());
    }

    @Test
    void thresholdExactlyEqualYieldsExactValue() {
        // elephant -> hippo is 7 edits; a threshold of exactly 7 still reports the accurate distance.
        final LevenshteinReceipt receipt = StringDistance.levenshtein("elephant", "hippo", 7, Unit.CODE_POINT);
        assertEquals(Status.EXACT, receipt.getStatus());
        assertEquals(7, receipt.getDistance().getAsInt());
        assertEquals(7, receipt.getVerifiedBound());

        final LevenshteinReceipt utf16 = StringDistance.levenshtein("elephant", "hippo", 7, Unit.UTF_16);
        assertEquals(Status.EXACT, utf16.getStatus());
        assertEquals(7, utf16.getDistance().getAsInt());

        // Empty-side boundary: distance equals the threshold exactly.
        final LevenshteinReceipt emptySide = StringDistance.levenshtein("aaapppp", "", 7, Unit.CODE_POINT);
        assertEquals(Status.EXACT, emptySide.getStatus());
        assertEquals(7, emptySide.getDistance().getAsInt());

        // Threshold zero with identical strings.
        final LevenshteinReceipt zero = StringDistance.levenshtein("same", "same", 0, Unit.UTF_16);
        assertEquals(Status.EXACT, zero.getStatus());
        assertEquals(0, zero.getDistance().getAsInt());
        assertEquals(0, zero.getVerifiedBound());
    }

    @Test
    void exceededThresholdReportsNoDistance() {
        // elephant -> hippo is 7 edits; a threshold of 6 can only report "exceeded".
        final LevenshteinReceipt receipt = StringDistance.levenshtein("elephant", "hippo", 6, Unit.CODE_POINT);
        assertEquals(Status.EXCEEDED_THRESHOLD, receipt.getStatus());
        assertFalse(receipt.isExact());
        assertFalse(receipt.getDistance().isPresent(), "no concrete distance may be fabricated");
        assertEquals(6, receipt.getVerifiedBound(), "verified boundary is the threshold that was exceeded");
        assertEquals(Unit.CODE_POINT, receipt.getUnit());

        // Provable from the length difference alone.
        final LevenshteinReceipt lengths = StringDistance.levenshtein("aaapppp", "", 6, Unit.UTF_16);
        assertEquals(Status.EXCEEDED_THRESHOLD, lengths.getStatus());
        assertFalse(lengths.getDistance().isPresent());
        assertEquals(6, lengths.getVerifiedBound());

        // Threshold zero with different strings.
        final LevenshteinReceipt zero = StringDistance.levenshtein("a", "b", 0, Unit.CODE_POINT);
        assertEquals(Status.EXCEEDED_THRESHOLD, zero.getStatus());
        assertFalse(zero.getDistance().isPresent());
    }

    @Test
    void emptyStringIsDistinctFromNull() {
        // Empty strings are regular inputs and compute normally.
        final LevenshteinReceipt bothEmpty = StringDistance.levenshtein("", "", 0, Unit.CODE_POINT);
        assertEquals(Status.EXACT, bothEmpty.getStatus());
        assertEquals(0, bothEmpty.getDistance().getAsInt());

        final LevenshteinReceipt emptyVsAbc = StringDistance.levenshtein("", "abc", Unit.UTF_16);
        assertEquals(Status.EXACT, emptyVsAbc.getStatus());
        assertEquals(3, emptyVsAbc.getDistance().getAsInt());

        // Null inputs are not computed at all, and are not conflated with empty strings.
        final LevenshteinReceipt nullLeft = StringDistance.levenshtein(null, "abc", Unit.CODE_POINT);
        assertEquals(Status.NOT_COMPUTED, nullLeft.getStatus());
        assertFalse(nullLeft.getDistance().isPresent());
        assertEquals(-1, nullLeft.getVerifiedBound());
        assertEquals(Unit.CODE_POINT, nullLeft.getUnit());

        final LevenshteinReceipt nullRight = StringDistance.levenshtein("abc", null, 3, Unit.UTF_16);
        assertEquals(Status.NOT_COMPUTED, nullRight.getStatus());
        assertEquals(3, nullRight.getThreshold());

        final LevenshteinReceipt nullNull = StringDistance.levenshtein(null, null, Unit.CODE_POINT);
        assertEquals(Status.NOT_COMPUTED, nullNull.getStatus());

        // A negative threshold is not a distance and is never "equal" to anything.
        final LevenshteinReceipt negative = StringDistance.levenshtein("a", "a", -1, Unit.CODE_POINT);
        assertEquals(Status.NOT_COMPUTED, negative.getStatus());
        assertFalse(negative.getDistance().isPresent());
    }

    @Test
    void fourStringExamples() {
        final String frog = "frog";
        final String fog = "fog";
        final String fly = "fly";
        final String ant = "ant";

        assertExact(1, frog, fog);
        assertExact(3, frog, fly);
        assertExact(4, frog, ant);
        assertExact(2, fog, fly);
        assertExact(3, fog, ant);
        assertExact(3, fly, ant);

        // Symmetry, and identical values in UTF-16 units for pure ASCII.
        assertExact(1, fog, frog);
        assertExact(4, ant, frog);
        assertEquals(2, StringDistance.levenshtein(fog, fly, Unit.UTF_16).getDistance().orElse(-1));
    }

    @Test
    void sourceInputsAreNotModified() {
        final StringBuilder left = new StringBuilder("kitten");
        final StringBuilder right = new StringBuilder("sitting");
        final LevenshteinReceipt receipt = StringDistance.levenshtein(left, right, 3, Unit.CODE_POINT);
        assertEquals(Status.EXACT, receipt.getStatus());
        assertEquals(3, receipt.getDistance().getAsInt());
        assertEquals("kitten", left.toString());
        assertEquals("sitting", right.toString());
    }

    @Test
    void unboundedOverloadMatchesClassicStringUtils() {
        assertEquals(3, StringDistance.levenshtein("kitten", "sitting", Unit.CODE_POINT).getDistance().orElse(-1));
        assertEquals(7, StringDistance.levenshtein("elephant", "hippo", Unit.UTF_16).getDistance().orElse(-1));
        assertEquals(Integer.MAX_VALUE, StringDistance.levenshtein("a", "b", Unit.CODE_POINT).getThreshold());
    }

    @Test
    void nullUnitIsRejected() {
        assertThrows(NullPointerException.class, () -> StringDistance.levenshtein("a", "b", 1, null));
    }

    private static void assertExact(final int expected, final String left, final String right) {
        final LevenshteinReceipt receipt = StringDistance.levenshtein(left, right, Unit.CODE_POINT);
        assertEquals(Status.EXACT, receipt.getStatus(), () -> left + " vs " + right);
        assertEquals(expected, receipt.getDistance().getAsInt(), () -> left + " vs " + right);
        assertEquals(expected, receipt.getVerifiedBound());
    }
}
