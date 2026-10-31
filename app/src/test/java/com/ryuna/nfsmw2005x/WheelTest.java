package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WheelTest {
    /** Drags the finger from startDeg through the given atan2 angles, as move() does; returns the wheel's angle. */
    private static float drag(float startDeg, float... path) {
        float last = startDeg, turn = 0;
        for (float a : path) {
            turn += Wheel.delta(last, a);
            last = a;
        }
        return Wheel.angle(turn, 120);
    }

    @Test
    public void deltaTakesTheShortWayRound() {
        assertEquals(20f, Wheel.delta(170, -170), 1e-4);
        assertEquals(-20f, Wheel.delta(-170, 170), 1e-4);
        assertEquals(30f, Wheel.delta(0, 30), 1e-4);
    }

    @Test
    public void clampsAtTheLock() {
        assertEquals(120f, drag(-90, -45, 0, 45, 90, 135), 1e-4);         // 225 degrees clockwise
        assertEquals(-120f, drag(-90, -135, 180, 135, 90, 45), 1e-4);
    }

    @Test
    public void pastTheLockAndBackIsStraightAgain() {
        // 225 degrees clockwise, past the 120 lock, then all the way back to where the finger came down
        assertEquals(0f, drag(-90, -45, 0, 45, 90, 135, 90, 45, 0, -45, -90), 1e-4);
        // and back only part way: still at the lock until the finger is inside it again
        assertEquals(120f, drag(-90, -45, 0, 45, 90, 135, 90), 1e-4);
        assertEquals(90f, drag(-90, -45, 0, 45, 90, 135, 90, 45, 0), 1e-4);
    }
}
