package dev.skytrade.util;

/** Injectable wall clock so simulations and backtests can run on virtual time. */
@FunctionalInterface
public interface TimeSource {
    long now();

    TimeSource SYSTEM = System::currentTimeMillis;

    /** Manually advanced clock for tests and replays. */
    final class Manual implements TimeSource {
        private volatile long time;

        public Manual(long start) {
            this.time = start;
        }

        @Override
        public long now() {
            return time;
        }

        public void set(long t) {
            time = t;
        }

        public void advance(long ms) {
            time += ms;
        }
    }
}
