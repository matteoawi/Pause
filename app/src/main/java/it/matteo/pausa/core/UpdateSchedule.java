package it.matteo.pausa.core;

/** Opportunistic updates only while protection is running; no alarms or wake locks. */
public final class UpdateSchedule {
    public static final long DAY = 24L * 60 * 60 * 1000;
    public static final long RETRY = 6L * 60 * 60 * 1000;
    public static boolean due(long now, long lastSuccess, long lastAttempt) {
        if (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < RETRY) return false;
        return lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= DAY;
    }
    private UpdateSchedule() { }
}
