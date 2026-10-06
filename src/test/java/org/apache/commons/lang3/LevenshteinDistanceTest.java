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

import org.apache.commons.lang3.LevenshteinDistance.Receipt;
import org.apache.commons.lang3.LevenshteinDistance.Status;
import org.apache.commons.lang3.LevenshteinDistance.Unit;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link LevenshteinDistance} and its explicit threshold {@link Receipt}.
 */
class LevenshteinDistanceTest {

    /** First of four example strings. */
    private static final String FROG = "frog";
    /** Second of four example strings. */
    private static final String FOG = "fog";
    /** Third of four example strings: two U+1F600 GRINNING FACE emoji. */
    private static final String GRINNING_GRINNING = "😀😀";
    /** Fourth of four example strings: U+1F600 GRINNING FACE followed by U+1FAE0 MELTING FACE. */
    private static final String GRINNING_MELTING = "😀🫠";

    @Test
    void testEmojiDistanceDiffersBetweenUnits() {
        // one code point substitution, but each emoji is a surrogate pair of UTF-16 units
        assertEquals(1, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, 10, Unit.CODE_POINT).getDistance());
        assertEquals(2, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, 10, Unit.UTF_16).getDistance());
        // single emoji pair: 1 code point vs 2 UTF-16 code units (both surrogate halves differ)
        assertEquals(1, LevenshteinDistance.distance("😀", "🫠", 10, Unit.CODE_POINT).getDistance());
        assertEquals(2, LevenshteinDistance.distance("😀", "🫠", 10, Unit.UTF_16).getDistance());
    }

    @Test
    void testEmptyStringIsNotNull() {
        // empty strings are genuine values and compute normally
        final Receipt emptyEmpty = LevenshteinDistance.distance("", "", 0, Unit.UTF_16);
        assertTrue(emptyEmpty.isExact());
        assertEquals(0, emptyEmpty.getDistance());
        final Receipt emptyAbc = LevenshteinDistance.distance("", "abc", 3, Unit.UTF_16);
        assertTrue(emptyAbc.isExact());
        assertEquals(3, emptyAbc.getDistance());
        // null is reported as NOT_COMPUTED, never confused with empty
        final Receipt nullLeft = LevenshteinDistance.distance(null, "abc", 3, Unit.UTF_16);
        assertEquals(Status.NOT_COMPUTED, nullLeft.getStatus());
        assertTrue(nullLeft.isNotComputed());
        assertFalse(nullLeft.getDistanceIfExact().isPresent());
        assertEquals(Status.NOT_COMPUTED, LevenshteinDistance.distance("abc", null, 3, Unit.UTF_16).getStatus());
        assertEquals(Status.NOT_COMPUTED, LevenshteinDistance.distance(null, null, 3, Unit.UTF_16).getStatus());
        assertEquals(Status.NOT_COMPUTED, LevenshteinDistance.distance(null, "", 0).getStatus());
    }

    @Test
    void testExceedsThresholdReportsNoDistance() {
        final Receipt receipt = LevenshteinDistance.distance("kitten", "sitting", 2, Unit.UTF_16);
        assertEquals(Status.EXCEEDS_THRESHOLD, receipt.getStatus());
        assertTrue(receipt.isExceedsThreshold());
        assertFalse(receipt.isExact());
        // no concrete distance is fabricated
        assertFalse(receipt.getDistanceIfExact().isPresent());
        assertThrows(IllegalStateException.class, receipt::getDistance);
        // the length difference alone can prove the excess
        assertEquals(Status.EXCEEDS_THRESHOLD, LevenshteinDistance.distance("", "abcdef", 3, Unit.UTF_16).getStatus());
        assertEquals(Status.EXCEEDS_THRESHOLD, LevenshteinDistance.distance("aaapppp", "", 6, Unit.UTF_16).getStatus());
    }

    @Test
    void testFourStringExamples() {
        // four example strings: FROG, FOG, GRINNING_GRINNING, GRINNING_MELTING
        assertEquals(1, LevenshteinDistance.distance(FROG, FOG, 1, Unit.UTF_16).getDistance());
        assertEquals(1, LevenshteinDistance.distance(FROG, FOG, 1, Unit.CODE_POINT).getDistance());
        assertEquals(0, LevenshteinDistance.distance(FROG, FROG, 0, Unit.UTF_16).getDistance());
        assertEquals(1, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, 1, Unit.CODE_POINT).getDistance());
        assertEquals(2, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, 2, Unit.UTF_16).getDistance());
        // cross pairs: "frog" vs two emoji
        assertEquals(4, LevenshteinDistance.distance(FROG, GRINNING_GRINNING, 10, Unit.UTF_16).getDistance());
        assertEquals(4, LevenshteinDistance.distance(FROG, GRINNING_GRINNING, 10, Unit.CODE_POINT).getDistance());
        // symmetry
        assertEquals(LevenshteinDistance.distance(FOG, GRINNING_MELTING, 10, Unit.UTF_16).getDistance(),
            LevenshteinDistance.distance(GRINNING_MELTING, FOG, 10, Unit.UTF_16).getDistance());
    }

    @Test
    void testNegativeThresholdIsRejected() {
        // a negative threshold must not be treated as a request for equality
        assertThrows(IllegalArgumentException.class, () -> LevenshteinDistance.distance("", "", -1, Unit.UTF_16));
        assertThrows(IllegalArgumentException.class, () -> LevenshteinDistance.distance("a", "a", -1, Unit.UTF_16));
        assertThrows(IllegalArgumentException.class, () -> LevenshteinDistance.distance(null, null, -1, Unit.UTF_16));
        assertThrows(IllegalArgumentException.class, () -> LevenshteinDistance.distance("a", "b", -2));
    }

    @Test
    void testReceiptCarriesUnitAndVerifiedThreshold() {
        final Receipt receipt = LevenshteinDistance.distance(FROG, FOG, 5, Unit.CODE_POINT);
        assertEquals(Unit.CODE_POINT, receipt.getUnit());
        assertEquals(5, receipt.getThreshold());
        assertEquals(Status.EXACT, receipt.getStatus());
        final Receipt exceeded = LevenshteinDistance.distance(FROG, GRINNING_MELTING, 1, Unit.UTF_16);
        assertEquals(Unit.UTF_16, exceeded.getUnit());
        assertEquals(1, exceeded.getThreshold());
        // even a NOT_COMPUTED receipt records the adopted unit and verified boundary
        final Receipt notComputed = LevenshteinDistance.distance(null, FOG, 7, Unit.CODE_POINT);
        assertEquals(Unit.CODE_POINT, notComputed.getUnit());
        assertEquals(7, notComputed.getThreshold());
    }

    @Test
    void testSourceInputIsNotModified() {
        final StringBuilder left = new StringBuilder("kitten");
        final StringBuilder right = new StringBuilder("sitting");
        final Receipt receipt = LevenshteinDistance.distance(left, right, 3, Unit.UTF_16);
        assertEquals(3, receipt.getDistance());
        assertEquals("kitten", left.toString());
        assertEquals("sitting", right.toString());
    }

    @Test
    void testThresholdExactlyEqualYieldsExactDistance() {
        Receipt receipt = LevenshteinDistance.distance("kitten", "sitting", 3, Unit.UTF_16);
        assertTrue(receipt.isExact());
        assertEquals(3, receipt.getDistance());
        assertEquals(3, receipt.getThreshold());
        // classic boundary cases from StringUtils
        assertEquals(7, LevenshteinDistance.distance("elephant", "hippo", 7, Unit.UTF_16).getDistance());
        assertEquals(7, LevenshteinDistance.distance("aaapppp", "", 7, Unit.UTF_16).getDistance());
        // one less than the exact distance only reports the excess
        receipt = LevenshteinDistance.distance("kitten", "sitting", 2, Unit.UTF_16);
        assertEquals(Status.EXCEEDS_THRESHOLD, receipt.getStatus());
        assertEquals(Status.EXCEEDS_THRESHOLD, LevenshteinDistance.distance("elephant", "hippo", 6, Unit.UTF_16).getStatus());
    }

    @Test
    void testUnboundedDistance() {
        assertEquals(0, LevenshteinDistance.distance("", "").getDistance());
        assertEquals(1, LevenshteinDistance.distance("", "a").getDistance());
        assertEquals(7, LevenshteinDistance.distance("aaapppp", "").getDistance());
        assertEquals(1, LevenshteinDistance.distance(FROG, FOG).getDistance());
        assertEquals(3, LevenshteinDistance.distance("fly", "ant").getDistance());
        assertEquals(7, LevenshteinDistance.distance("elephant", "hippo").getDistance());
        assertEquals(8, LevenshteinDistance.distance("hippo", "zzzzzzzz").getDistance());
        assertEquals(1, LevenshteinDistance.distance("hello", "hallo").getDistance());
        assertEquals(1, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, Unit.CODE_POINT).getDistance());
        assertEquals(2, LevenshteinDistance.distance(GRINNING_GRINNING, GRINNING_MELTING, Unit.UTF_16).getDistance());
        assertEquals(Status.NOT_COMPUTED, LevenshteinDistance.distance(null, "a").getStatus());
    }
}
