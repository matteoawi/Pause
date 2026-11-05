package it.matteo.pausa.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateScheduleTest {
    private static final long NOW = 100 * UpdateSchedule.DAY;
    @Test public void freshInstallIsDue() { assertTrue(UpdateSchedule.due(NOW, 0, 0)); }
    @Test public void successWaitsOneDay() {
        assertFalse(UpdateSchedule.due(NOW, NOW - UpdateSchedule.DAY + 1, NOW - UpdateSchedule.RETRY));
        assertTrue(UpdateSchedule.due(NOW, NOW - UpdateSchedule.DAY, NOW - UpdateSchedule.DAY));
    }
    @Test public void failureDoesNotRetryContinuously() {
        assertFalse(UpdateSchedule.due(NOW, 0, NOW - UpdateSchedule.RETRY + 1));
        assertTrue(UpdateSchedule.due(NOW, 0, NOW - UpdateSchedule.RETRY));
    }
    @Test public void clockRollbackDoesNotDisableUpdates() {
        assertTrue(UpdateSchedule.due(NOW, NOW + UpdateSchedule.DAY, NOW + UpdateSchedule.DAY));
        assertFalse(UpdateSchedule.due(NOW, NOW + UpdateSchedule.DAY, NOW));
    }
}
