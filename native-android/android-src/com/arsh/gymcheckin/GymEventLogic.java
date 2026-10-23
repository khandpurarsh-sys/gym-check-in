package com.arsh.gymcheckin;

/** Pure decision logic for geofence event delivery. No Android imports - unit-testable on the JVM. */
public final class GymEventLogic {
    private GymEventLogic() {}

    public static final long DEDUP_WINDOW_MS = 180_000L;
    public static final long ENTER_STALE_CUTOFF_MS = 15 * 60_000L;
    public static final long EXIT_STALE_CUTOFF_MS = 12 * 3600_000L;
    public static final long FUTURE_SKEW_MS = 60_000L;
    public static final int MAX_ATTEMPTS = 6;

    public static final int DONE = 0;   // posted, definitively rejected, or deliberately dropped
    public static final int RETRY = 1;  // transient failure, try again later

    /** Same transition repeated within the dedup window is a boundary-flap duplicate. */
    public static boolean isDuplicate(int lastTransition, long lastAt, int transition, long now) {
        return lastTransition == transition && now >= lastAt && (now - lastAt) < DEDUP_WINDOW_MS;
    }

    /**
     * The backend timestamps server-side, so a stale ENTER replayed now would create
     * false current attendance - drop it fast. A late EXIT only stretches the end of a
     * real open visit instead of fabricating one, so its cutoff is much longer.
     * Events dated in the future are always dropped.
     */
    public static boolean isStale(long occurredAt, long now, boolean checkIn) {
        long cutoff = checkIn ? ENTER_STALE_CUTOFF_MS : EXIT_STALE_CUTOFF_MS;
        return (now - occurredAt) > cutoff || occurredAt > now + FUTURE_SKEW_MS;
    }

    /** A later EXIT already recorded for this visit: the visit completed in the past - drop the ENTER instead of posting a fake "now" visit. */
    public static boolean coalescedByExit(int lastTransition, long lastAt, long occurredAt) {
        return lastTransition == 2 && lastAt > occurredAt; // 2 = GEOFENCE_TRANSITION_EXIT
    }

    /**
     * EXIT may post only against the currently open visit it belongs to:
     * an open visit must exist and must have started no later than the EXIT occurred,
     * so a stale EXIT can never close a newer visit.
     */
    public static boolean exitAllowed(Long openVisitInAtMs, long occurredAt) {
        return openVisitInAtMs != null && openVisitInAtMs <= occurredAt;
    }

    /** Event belongs to the currently armed config; else it must never post. */
    public static boolean configMatches(String currentCode, String eventCode) {
        return currentCode != null && currentCode.equals(eventCode);
    }

    /**
     * @param httpStatus null on I/O error/timeout, else the HTTP status
     * @param ok         backend ok flag (only meaningful on 2xx)
     */
    public static int postOutcome(Integer httpStatus, boolean ok, int attempt, int maxAttempts) {
        if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) return DONE; // posted or definitively rejected: both terminal
        if (attempt >= maxAttempts - 1) return DONE; // retry budget exhausted, drop rather than risk stale replay
        return RETRY;
    }
}
