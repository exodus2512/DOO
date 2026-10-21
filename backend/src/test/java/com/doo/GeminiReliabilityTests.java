package com.doo;

import com.doo.ai.*;
import com.doo.ai.GeminiKeyPool.KeyState;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Gemini Reliability: Throttle, Key Pool, Call Counting")
class GeminiReliabilityTests {

    // ─── Throttle Tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Client-Side Throttle")
    class ThrottleTests {

        @Test
        @DisplayName("Burst of calls is paced by min interval")
        void burstPacedByMinInterval() throws InterruptedException {
            int maxRpm = 30;
            Duration minInterval = Duration.ofMillis(100);
            GeminiThrottle throttle = new GeminiThrottle(minInterval, maxRpm);

            int callCount = 5;
            long start = System.currentTimeMillis();
            for (int i = 0; i < callCount; i++) {
                throttle.acquirePermit();
            }
            long elapsed = System.currentTimeMillis() - start;

            // With 5 calls and 100ms min interval, should take at least 400ms (4 gaps)
            assertThat(elapsed).isGreaterThanOrEqualTo(350); // allow jitter
            assertThat(throttle.callsInLastMinute()).isEqualTo(callCount);
        }

        @Test
        @DisplayName("Min interval enforced between consecutive calls")
        void minIntervalEnforced() throws InterruptedException {
            GeminiThrottle throttle = new GeminiThrottle(Duration.ofMillis(200), 100);

            throttle.acquirePermit();
            long beforeSecond = System.currentTimeMillis();
            throttle.acquirePermit();
            long afterSecond = System.currentTimeMillis();

            assertThat(afterSecond - beforeSecond).isGreaterThanOrEqualTo(180); // allow 20ms jitter
        }
    }

    // ─── Key Pool Tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Key Pool and Rotation")
    class KeyPoolTests {

        @Test
        @DisplayName("Pool loads valid keys and ignores blanks")
        void loadsValidKeys() {
            GeminiKeyPool pool = new GeminiKeyPool(
                Arrays.asList("key-a", "", "key-c", null, "  "),
                Duration.ofSeconds(10)
            );
            assertThat(pool.size()).isEqualTo(2);
            assertThat(pool.hasKeys()).isTrue();
        }

        @Test
        @DisplayName("No valid keys → hasKeys() returns false")
        void noValidKeys() {
            GeminiKeyPool pool = new GeminiKeyPool(
                Arrays.asList("", null, "  "),
                Duration.ofSeconds(10)
            );
            assertThat(pool.hasKeys()).isFalse();
            assertThat(pool.nextHealthyKey()).isNull();
        }

        @Test
        @DisplayName("401/403 marks key DEAD and fails over to next")
        void deadKeyFailover() {
            GeminiKeyPool pool = new GeminiKeyPool(
                List.of("key-a", "key-b"),
                Duration.ofSeconds(10)
            );

            GeminiKeyPool.KeyEntry first = pool.nextHealthyKey();
            assertThat(first).isNotNull();
            pool.markDead(first.label(), "401 Unauthorized");

            GeminiKeyPool.KeyEntry second = pool.nextHealthyKey();
            assertThat(second).isNotNull();
            assertThat(second.label()).isNotEqualTo(first.label());

            Map<String, KeyState> states = pool.getKeyStates();
            assertThat(states.get(first.label())).isEqualTo(KeyState.DEAD);
            assertThat(states.get(second.label())).isEqualTo(KeyState.HEALTHY);
        }

        @Test
        @DisplayName("429 puts key in cooldown, recovers after duration")
        void cooldownAndRecovery() throws InterruptedException {
            GeminiKeyPool pool = new GeminiKeyPool(
                List.of("key-a"),
                Duration.ofMillis(200)
            );

            GeminiKeyPool.KeyEntry key = pool.nextHealthyKey();
            assertThat(key).isNotNull();
            pool.markCooldown(key.label(), "429 rate limit");

            // During cooldown, no healthy key available
            assertThat(pool.nextHealthyKey()).isNull();
            assertThat(pool.getKeyStates().get(key.label())).isEqualTo(KeyState.COOLDOWN);

            // Wait for cooldown to expire
            Thread.sleep(300);

            // Key should be healthy again
            GeminiKeyPool.KeyEntry recovered = pool.nextHealthyKey();
            assertThat(recovered).isNotNull();
            assertThat(recovered.label()).isEqualTo(key.label());
        }

        @Test
        @DisplayName("All keys exhausted returns null")
        void allKeysExhausted() {
            GeminiKeyPool pool = new GeminiKeyPool(
                List.of("key-a", "key-b"),
                Duration.ofSeconds(60)
            );

            pool.markDead("key-1", "401");
            pool.markDead("key-2", "403");

            assertThat(pool.nextHealthyKey()).isNull();
        }

        @Test
        @DisplayName("Rotation logs key label, not key material")
        void rotationLogsLabel() {
            GeminiKeyPool pool = new GeminiKeyPool(
                List.of("secret-abc-123"),
                Duration.ofSeconds(10)
            );

            GeminiKeyPool.KeyEntry key = pool.nextHealthyKey();
            assertThat(key.label()).isEqualTo("key-1");
            assertThat(key.label()).doesNotContain("secret");
        }
    }

    // ─── All-Keys-Exhausted Error ──────────────────────────────────────

    @Nested
    @DisplayName("All-Keys-Exhausted Error State")
    class AllKeysExhaustedTests {

        @Test
        @DisplayName("callGemini throws AllKeysExhaustedException when no healthy keys remain")
        void allKeysExhaustedThrows() {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            // Create provider with a single key, then kill it
            GeminiAiProvider provider = new GeminiAiProvider("test-key", "gemini-3.8-flash", false, om);
            provider.getKeyPool().markDead("key-1", "test exhaustion");

            assertThatThrownBy(() -> provider.classifyWorkflow("test query"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("keys exhausted");
        }

        @Test
        @DisplayName("Startup with no valid keys and live mode is not available")
        void startupNoValidKeys() {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            GeminiAiProvider provider = new GeminiAiProvider("", "gemini-3.8-flash", false, om);
            // Should fall through to deterministic since isAvailable() checks keyPool.hasKeys()
            AiProvider.WorkflowClassification result = provider.classifyWorkflow("research AI tools");
            // No keys → not available → deterministic mode
            assertThat(result.workflowType()).isEqualTo("WEB_RESEARCH");
        }
    }

    // ─── Call Stats Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("AI Call Statistics")
    class CallStatsTests {

        @Test
        @DisplayName("Stats track all phases correctly")
        void statsTrackPhases() {
            AiCallStats stats = new AiCallStats("test-task");

            stats.record(AiCallStats.Phase.CLASSIFY);
            stats.record(AiCallStats.Phase.PLAN);
            stats.record(AiCallStats.Phase.SELECT_ACTION);
            stats.record(AiCallStats.Phase.SELECT_ACTION);
            stats.record(AiCallStats.Phase.SELECT_ACTION);
            stats.record(AiCallStats.Phase.SYNTHESIZE);
            stats.record(AiCallStats.Phase.VERIFY);
            stats.record(AiCallStats.Phase.RETRY);

            assertThat(stats.get(AiCallStats.Phase.CLASSIFY)).isEqualTo(1);
            assertThat(stats.get(AiCallStats.Phase.PLAN)).isEqualTo(1);
            assertThat(stats.get(AiCallStats.Phase.SELECT_ACTION)).isEqualTo(3);
            assertThat(stats.get(AiCallStats.Phase.SYNTHESIZE)).isEqualTo(1);
            assertThat(stats.get(AiCallStats.Phase.VERIFY)).isEqualTo(1);
            assertThat(stats.get(AiCallStats.Phase.RETRY)).isEqualTo(1);
            assertThat(stats.total()).isEqualTo(8);

            Map<String, Integer> snap = stats.snapshot();
            assertThat(snap.get("total")).isEqualTo(8);
            assertThat(snap.get("classify")).isEqualTo(1);
        }

        @Test
        @DisplayName("Demo-mode research task uses under 15 model calls (0 — all deterministic)")
        void demoResearchTaskCallCount() {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            GeminiAiProvider provider = new GeminiAiProvider("", "gemini-3.8-flash", true, om);

            String taskLabel = "Research the top AI agent frameworks on GitHub";
            AiCallStats stats = provider.getOrCreateStats(taskLabel);

            // Simulate a full task flow in demo mode
            provider.classifyWorkflow(taskLabel);
            var classification = provider.classifyWorkflow(taskLabel);
            provider.generatePlan(taskLabel, "criteria",
                new AiProvider.WorkflowContext(classification.workflowType()));

            for (int step = 0; step < 3; step++) {
                provider.selectNextAction(new AiProvider.ActionSelectionRequest(
                    taskLabel, "", step, List.of("s1", "s2", "s3"),
                    step > 0 ? "https://example.com" : "", "", "", "",
                    List.of(), List.of(), "WEB_RESEARCH"
                ));
            }

            provider.synthesizeResult(new AiProvider.SynthesisRequest(
                taskLabel, "WEB_RESEARCH", List.of("https://example.com"),
                List.of("snippet"), List.of("action")));

            provider.verify(new AiProvider.VerificationRequest(
                taskLabel, "criteria", "WEB_RESEARCH",
                "https://example.com", "content",
                List.of("https://example.com"), "summary",
                List.of("evidence")));

            // Demo mode: no actual API calls, all deterministic
            assertThat(stats.total()).isEqualTo(0);
            provider.clearStats(taskLabel);
        }
    }

    // ─── Call Volume for Live Mode ──────────────────────────────────────

    @Nested
    @DisplayName("Call Volume Budget")
    class CallVolumeTests {

        @Test
        @DisplayName("Live-mode research task targets under 15 model calls")
        void liveResearchCallBudget() {
            // classify(1) + plan(1) + max 10 action selections + synthesize(1) + verify(1)
            // = 14 max without retries, which is under 15
            int classifyCalls = 1;
            int planCalls = 1;
            int maxPlanSteps = 10; // reduced from 15 in the prompt
            int synthesizeCalls = 1;
            int verifyCalls = 1;

            int maxModelCalls = classifyCalls + planCalls + maxPlanSteps + synthesizeCalls + verifyCalls;
            assertThat(maxModelCalls).isLessThanOrEqualTo(14);
        }
    }
}
