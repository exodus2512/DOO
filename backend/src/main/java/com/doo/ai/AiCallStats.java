package com.doo.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AiCallStats {

    public enum Phase {
        CLASSIFY, PLAN, SELECT_ACTION, SYNTHESIZE, VERIFY, RETRY
    }

    private final ConcurrentHashMap<Phase, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final String taskLabel;

    public AiCallStats(String taskLabel) {
        this.taskLabel = taskLabel;
        for (Phase p : Phase.values()) {
            counts.put(p, new AtomicInteger(0));
        }
    }

    public void record(Phase phase) {
        counts.get(phase).incrementAndGet();
    }

    public int get(Phase phase) {
        return counts.get(phase).get();
    }

    public int total() {
        return counts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> snap = new java.util.LinkedHashMap<>();
        for (Phase p : Phase.values()) {
            int v = counts.get(p).get();
            if (v > 0) snap.put(p.name().toLowerCase(), v);
        }
        snap.put("total", total());
        return snap;
    }

    public String taskLabel() {
        return taskLabel;
    }
}
