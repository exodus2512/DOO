package com.doo.ai;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class GeminiKeyPool {

    public enum KeyState { HEALTHY, COOLDOWN, DEAD }

    public record KeyEntry(String label, String key, KeyState state, Instant cooldownUntil) {
        public KeyEntry withState(KeyState newState, Instant cooldownUntil) {
            return new KeyEntry(label, key, newState, cooldownUntil);
        }
    }

    private final List<KeyEntry> keys;
    private final AtomicInteger index = new AtomicInteger(0);
    private final Duration cooldownDuration;
    private final ReentrantLock lock = new ReentrantLock();

    public GeminiKeyPool(List<String> apiKeys, Duration cooldownDuration) {
        this.cooldownDuration = cooldownDuration;
        List<KeyEntry> entries = new ArrayList<>();
        for (int i = 0; i < apiKeys.size(); i++) {
            String key = apiKeys.get(i);
            if (key != null && !key.isBlank()) {
                entries.add(new KeyEntry("key-" + (i + 1), key, KeyState.HEALTHY, Instant.MIN));
            }
        }
        this.keys = entries;
        if (entries.isEmpty()) {
            log.warn("GeminiKeyPool initialized with no valid keys — live mode unavailable");
        } else {
            log.info("GeminiKeyPool initialized with {} key(s): {}", entries.size(),
                entries.stream().map(KeyEntry::label).toList());
        }
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }

    public KeyEntry nextHealthyKey() {
        lock.lock();
        try {
            if (keys.isEmpty()) return null;

            Instant now = Instant.now();
            // Promote cooldown keys whose timer expired
            for (int i = 0; i < keys.size(); i++) {
                KeyEntry e = keys.get(i);
                if (e.state() == KeyState.COOLDOWN && now.isAfter(e.cooldownUntil())) {
                    keys.set(i, e.withState(KeyState.HEALTHY, Instant.MIN));
                    log.info("Key {} recovered from cooldown", e.label());
                }
            }

            int start = index.get() % keys.size();
            for (int i = 0; i < keys.size(); i++) {
                int idx = (start + i) % keys.size();
                KeyEntry e = keys.get(idx);
                if (e.state() == KeyState.HEALTHY) {
                    index.set(idx + 1);
                    return e;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void markDead(String label, String reason) {
        lock.lock();
        try {
            for (int i = 0; i < keys.size(); i++) {
                KeyEntry e = keys.get(i);
                if (e.label().equals(label)) {
                    keys.set(i, e.withState(KeyState.DEAD, Instant.MAX));
                    log.error("Key {} marked DEAD: {}", label, reason);
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void markCooldown(String label, String reason) {
        lock.lock();
        try {
            for (int i = 0; i < keys.size(); i++) {
                KeyEntry e = keys.get(i);
                if (e.label().equals(label)) {
                    Instant until = Instant.now().plus(cooldownDuration);
                    keys.set(i, e.withState(KeyState.COOLDOWN, until));
                    log.warn("Key {} entering cooldown for {}s: {}", label, cooldownDuration.toSeconds(), reason);
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public Map<String, KeyState> getKeyStates() {
        lock.lock();
        try {
            Instant now = Instant.now();
            Map<String, KeyState> states = new LinkedHashMap<>();
            for (KeyEntry e : keys) {
                KeyState effective = e.state();
                if (effective == KeyState.COOLDOWN && now.isAfter(e.cooldownUntil())) {
                    effective = KeyState.HEALTHY;
                }
                states.put(e.label(), effective);
            }
            return states;
        } finally {
            lock.unlock();
        }
    }
}
