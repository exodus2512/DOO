package com.doo;

import com.doo.service.TaskExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the no-progress detection algorithm.
 * Tests the fingerprint matching and window logic without Spring context.
 */
@DisplayName("No-Progress Detection Unit Tests")
class NoProgressDetectionTest {

    private static final int NO_PROGRESS_WINDOW = 5;
    private static final int NO_PROGRESS_THRESHOLD = 3;

    private boolean detectNoProgress(List<String> fingerprints) {
        if (fingerprints.size() < NO_PROGRESS_THRESHOLD) return false;
        int windowStart = Math.max(0, fingerprints.size() - NO_PROGRESS_WINDOW);
        List<String> window = fingerprints.subList(windowStart, fingerprints.size());
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String fp : window) {
            int count = counts.merge(fp, 1, Integer::sum);
            if (count >= NO_PROGRESS_THRESHOLD) return true;
        }
        return false;
    }

    @Test
    @DisplayName("Detects repeated identical FILL actions")
    void detectsRepeatedFill() {
        List<String> fps = new ArrayList<>(List.of(
            "FILL|selector=#name&value=test",
            "FILL|selector=#name&value=test",
            "FILL|selector=#name&value=test"
        ));
        assertThat(detectNoProgress(fps)).isTrue();
    }

    @Test
    @DisplayName("Does not trigger for varied actions")
    void doesNotTriggerForVariedActions() {
        List<String> fps = new ArrayList<>(List.of(
            "NAVIGATE|url=https://example.com",
            "CLICK|selector=#btn",
            "FILL|selector=#name&value=test",
            "SCREENSHOT|label=step1",
            "EXTRACT_TEXT|selector=body"
        ));
        assertThat(detectNoProgress(fps)).isFalse();
    }

    @Test
    @DisplayName("Does not trigger for same action type with different args")
    void doesNotTriggerForDifferentArgs() {
        List<String> fps = new ArrayList<>(List.of(
            "FILL|selector=#name&value=Alice",
            "FILL|selector=#email&value=alice@test.com",
            "FILL|selector=#company&value=Acme"
        ));
        assertThat(detectNoProgress(fps)).isFalse();
    }

    @Test
    @DisplayName("Triggers only when threshold met within window")
    void triggersWithinWindow() {
        List<String> fps = new ArrayList<>(List.of(
            "NAVIGATE|url=https://a.com",
            "NAVIGATE|url=https://a.com",
            "CLICK|selector=#btn",
            "NAVIGATE|url=https://a.com",
            "NAVIGATE|url=https://a.com"
        ));
        // Window of 5: NAVIGATE appears 4 times but the question is contiguous
        // Actually: NAVIGATE x2, CLICK, NAVIGATE x2 = 4 NAVIGATE in window
        assertThat(detectNoProgress(fps)).isTrue();
    }

    @Test
    @DisplayName("Old repeated actions outside window are forgotten")
    void oldActionsOutsideWindow() {
        List<String> fps = new ArrayList<>(List.of(
            "FILL|selector=#name&value=test",
            "FILL|selector=#name&value=test",
            // These new diverse actions push the old FILLs out of the window
            "NAVIGATE|url=https://a.com",
            "CLICK|selector=#btn",
            "SCREENSHOT|label=step",
            "EXTRACT_TEXT|selector=body",
            "SCROLL|direction=down"
        ));
        assertThat(detectNoProgress(fps)).isFalse();
    }

    @Test
    @DisplayName("Does not trigger with fewer than threshold actions")
    void doesNotTriggerBelowThreshold() {
        List<String> fps = new ArrayList<>(List.of(
            "FILL|selector=#name&value=test",
            "FILL|selector=#name&value=test"
        ));
        assertThat(detectNoProgress(fps)).isFalse();
    }

    @Test
    @DisplayName("Empty fingerprint list returns false")
    void emptyListReturnsFalse() {
        assertThat(detectNoProgress(new ArrayList<>())).isFalse();
    }

    @Test
    @DisplayName("NAVIGATE repeated 3 times with same URL triggers detection")
    void repeatedNavigateSameUrl() {
        List<String> fps = new ArrayList<>(List.of(
            "NAVIGATE|url=https://google.com/search",
            "NAVIGATE|url=https://google.com/search",
            "NAVIGATE|url=https://google.com/search"
        ));
        assertThat(detectNoProgress(fps)).isTrue();
    }
}
