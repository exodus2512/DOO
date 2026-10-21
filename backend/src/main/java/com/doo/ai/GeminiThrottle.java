package com.doo.ai;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

@Slf4j
public class GeminiThrottle {

    private final Duration minInterval;
    private final int maxCallsPerMinute;
    private final Deque<Instant> callTimestamps = new ArrayDeque<>();
    private Instant lastCallTime = Instant.MIN;

    public GeminiThrottle(Duration minInterval, int maxCallsPerMinute) {
        this.minInterval = minInterval;
        this.maxCallsPerMinute = maxCallsPerMinute;
    }

    public synchronized void acquirePermit() throws InterruptedException {
        Instant now = Instant.now();

        // Enforce minimum interval
        Duration sinceLastCall = Duration.between(lastCallTime, now);
        if (sinceLastCall.compareTo(minInterval) < 0) {
            long waitMs = minInterval.minus(sinceLastCall).toMillis();
            log.debug("Throttle: waiting {}ms for min interval", waitMs);
            Thread.sleep(waitMs);
            now = Instant.now();
        }

        // Enforce RPM window
        Instant windowStart = now.minus(Duration.ofMinutes(1));
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().isBefore(windowStart)) {
            callTimestamps.pollFirst();
        }
        if (callTimestamps.size() >= maxCallsPerMinute) {
            Instant oldest = callTimestamps.peekFirst();
            long waitMs = Duration.between(now, oldest.plus(Duration.ofMinutes(1))).toMillis();
            if (waitMs > 0) {
                log.info("Throttle: RPM limit ({}) reached, waiting {}ms", maxCallsPerMinute, waitMs);
                Thread.sleep(waitMs);
                now = Instant.now();
                // Re-prune
                windowStart = now.minus(Duration.ofMinutes(1));
                while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().isBefore(windowStart)) {
                    callTimestamps.pollFirst();
                }
            }
        }

        callTimestamps.addLast(now);
        lastCallTime = now;
    }

    public synchronized int callsInLastMinute() {
        Instant windowStart = Instant.now().minus(Duration.ofMinutes(1));
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().isBefore(windowStart)) {
            callTimestamps.pollFirst();
        }
        return callTimestamps.size();
    }
}
