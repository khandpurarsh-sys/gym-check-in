package com.arsh.gymcheckin;

/** Pure decision logic for geofence event delivery. No Android imports - unit-testable on the JVM. */
public final class GymEventLogic {
    private GymEventLogic() {}

    public static final long DEDUP_WINDOW_MS = 180_000L;
    public static final long STALE_CUTOFF_MS = 15 * 60_000L;
    public static final long FUTURE_SKEW_MS = 60_000L;
    public static final int MAX_ATTEMPTS = 6;

    public static final int DONE = 0;   // posted, definitively rejected, or deliberately dropped
    public static final int RETRY = 1;  // transient failure, try again later

    /** Same transition repeated within the dedup window is a boundary-flap duplicate. */
    public static boolean isDuplicate(int lastTransition, long lastAt, int transition, long now) {
        return lastTransition == transition && now >= lastAt && (now - lastAt) < DEDUP_WINDOW_MS;
    }

    /**
     * The backend timestamps server-side, so a stale event replayed now would create
     * false current attendance. Drop events older than the cutoff or from the future.
     */
    public static boolean isStale(long occurredAt, long now) {
        return (now - occurredAt) > STALE_CUTOFF_MS || occurredAt > now + FUTURE_SKEW_MS;
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
