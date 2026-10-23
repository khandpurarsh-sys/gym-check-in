package com.arsh.gymcheckin;

import org.junit.Test;
import static org.junit.Assert.*;

public class GymEventLogicTest {
    private static final int ENTER = 1, EXIT = 2;
    private static final long NOW = 1_700_000_000_000L;

    @Test public void duplicateWithinWindowDropped() {
        assertTrue(GymEventLogic.isDuplicate(ENTER, NOW - 60_000, ENTER, NOW));
    }
    @Test public void sameTransitionAfterWindowAllowed() {
        assertFalse(GymEventLogic.isDuplicate(ENTER, NOW - 200_000, ENTER, NOW));
    }
    @Test public void differentTransitionNeverDuplicate() {
        assertFalse(GymEventLogic.isDuplicate(ENTER, NOW - 5_000, EXIT, NOW));
    }
    @Test public void clockSkewNegativeGapNotDuplicate() {
        assertFalse(GymEventLogic.isDuplicate(ENTER, NOW + 10_000, ENTER, NOW));
    }

    @Test public void staleEventDropped() {
        assertTrue(GymEventLogic.isStale(NOW - 16 * 60_000L, NOW));
    }
    @Test public void freshEventKept() {
        assertFalse(GymEventLogic.isStale(NOW - 60_000L, NOW));
    }
    @Test public void futureEventDropped() {
        assertTrue(GymEventLogic.isStale(NOW + 120_000L, NOW));
    }

    @Test public void logoutDropsEvent() {
        assertFalse(GymEventLogic.configMatches(null, "abc"));
    }
    @Test public void rotatedCodeDropsEvent() {
        assertFalse(GymEventLogic.configMatches("new", "old"));
    }
    @Test public void matchingCodeAllowed() {
        assertTrue(GymEventLogic.configMatches("same", "same"));
    }

    @Test public void postedOkIsDone() {
        assertEquals(GymEventLogic.DONE, GymEventLogic.postOutcome(200, true, 0, 6));
    }
    @Test public void definitiveRejectionIsDone() {
        assertEquals(GymEventLogic.DONE, GymEventLogic.postOutcome(200, false, 0, 6));
    }
    @Test public void serverErrorRetries() {
        assertEquals(GymEventLogic.RETRY, GymEventLogic.postOutcome(500, false, 0, 6));
    }
    @Test public void ioErrorRetries() {
        assertEquals(GymEventLogic.RETRY, GymEventLogic.postOutcome(null, false, 0, 6));
    }
    @Test public void exhaustedAttemptsDrop() {
        assertEquals(GymEventLogic.DONE, GymEventLogic.postOutcome(null, false, 5, 6));
        assertEquals(GymEventLogic.DONE, GymEventLogic.postOutcome(500, false, 5, 6));
    }
    @Test public void beforeCapRetries() {
        assertEquals(GymEventLogic.RETRY, GymEventLogic.postOutcome(null, false, 4, 6));
    }
}
