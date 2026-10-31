package com.ryuna.nfsmw2005x;

/**
 * The touch wheel's turning (TouchControllerView): the finger's angle round the hub summed from where it came down,
 * unbounded, and the wheel's angle that sum clamped to the lock. Kept apart from the view so it runs on the JVM.
 */
final class Wheel {
    private Wheel() { }

    /** The finger's turn from prevDeg to nowDeg (atan2 degrees), the short way round: -180..180. */
    static float delta(float prevDeg, float nowDeg) {
        float d = nowDeg - prevDeg;
        if (d > 180) d -= 360; else if (d < -180) d += 360;
        return d;
    }

    /** The wheel's angle for a turn of turnDeg since touch-down: past the lock it stays at the lock, and coming back
     *  it leaves the lock only once the finger is back inside it, so the touch-down angle is always straight ahead. */
    static float angle(float turnDeg, float lockDeg) {
        return Math.max(-lockDeg, Math.min(lockDeg, turnDeg));
    }
}
