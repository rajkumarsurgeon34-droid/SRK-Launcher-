package in.shazverse.srklauncher;

import android.animation.TimeInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** iOS-like motion curves. */
final class Motion {
    private Motion() {}

    /** Damped spring: quick start, tiny overshoot, soft landing. */
    static final class Spring implements Interpolator, TimeInterpolator {
        private final float damping, freq;

        Spring(float damping, float freq) {
            this.damping = damping;
            this.freq = freq;
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            return (float) (1 - Math.exp(-damping * t) * Math.cos(freq * t));
        }
    }

    static final Spring SPRING = new Spring(7.5f, 10f);
    static final Spring SOFT_SPRING = new Spring(6.5f, 6.5f);
    /** Apple-style "ease out" used for page swipes and sheets. */
    static final PathInterpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    static final PathInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    /** Overlays that animate themselves in and out. */
    interface OverlayAnim {
        void enter();

        void exit(Runnable done);
    }
}
