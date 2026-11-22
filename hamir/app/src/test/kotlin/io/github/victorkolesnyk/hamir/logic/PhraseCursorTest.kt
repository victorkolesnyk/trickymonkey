package io.github.victorkolesnyk.hamir.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhraseCursorTest {
    @Test
    fun firstUnrecordedSkipsRecordedPhrases() {
        assertEquals(1, PhraseCursor.firstUnrecorded(emptySet(), 100))
        assertEquals(4, PhraseCursor.firstUnrecorded(setOf(1, 2, 3, 7), 100))
        assertEquals(1, PhraseCursor.firstUnrecorded((1..100).toSet(), 100))
    }

    @Test
    fun nextAndPreviousWrapAround() {
        assertEquals(2, PhraseCursor.next(1, 100))
        assertEquals(1, PhraseCursor.next(100, 100))
        assertEquals(100, PhraseCursor.previous(1, 100))
        assertEquals(41, PhraseCursor.previous(42, 100))
    }

    @Test
    fun nextUnrecordedWrapsAndReportsCompletion() {
        assertEquals(5, PhraseCursor.nextUnrecorded(3, setOf(4), 10))
        assertEquals(2, PhraseCursor.nextUnrecorded(9, setOf(10, 1), 10))
        assertEquals(3, PhraseCursor.nextUnrecorded(3, (1..10).toSet() - 3, 10))
        assertNull(PhraseCursor.nextUnrecorded(3, (1..10).toSet(), 10))
    }

    @Test
    fun clampKeepsNumberInRange() {
        assertEquals(1, PhraseCursor.clamp(0, 100))
        assertEquals(100, PhraseCursor.clamp(250, 100))
        assertEquals(55, PhraseCursor.clamp(55, 100))
    }

    @Test
    fun readinessThresholds() {
        assertEquals(false, EnrollmentRules.isReady(19))
        assertEquals(true, EnrollmentRules.isReady(20))
        assertEquals(true, ProfileStats(60, 200.0).ready)
    }
}
