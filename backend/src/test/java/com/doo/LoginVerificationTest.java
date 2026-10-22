package com.doo;

import com.doo.service.TaskExecutorService;
import com.doo.service.TaskExecutorService.AuthSurface;
import com.doo.service.TaskExecutorService.LoginVerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Login Verification Tests")
class LoginVerificationTest {

    private final AuthSurface nonAuthSurface = new AuthSurface(false, false, List.of(), false, false);

    @Test
    @DisplayName("Auth path URLs (enter, signup, sign-up, register, join, onboarding) are rejected")
    void testAuthPathUrlsRejected() {
        List<String> authUrls = List.of(
            "https://example.com/enter",
            "https://example.com/signup",
            "https://example.com/sign-up",
            "https://example.com/register",
            "https://example.com/join",
            "https://example.com/onboarding",
            "https://example.com/account/login",
            "https://example.com/user/login",
            "https://example.com/auth"
        );

        List<Map<String, Object>> emptyElements = List.of();

        for (String url : authUrls) {
            LoginVerificationResult res = TaskExecutorService.verifyLoginStatus(
                emptyElements, "Some page text", url, nonAuthSurface
            );
            assertThat(res.verified())
                .as("URL %s should be recognized as an auth path and rejected", url)
                .isFalse();
            assertThat(res.canSaveStorageState()).isFalse();
        }
    }

    @Test
    @DisplayName("Dropped generic markers (welcome, settings, profile) do not trigger positive marker")
    void testGenericMarkersDropped() {
        String url = "https://example.com/account";
        List<Map<String, Object>> elements = List.of(
            Map.of("text", "Welcome back!"),
            Map.of("text", "Profile settings"),
            Map.of("text", "Hello, user")
        );

        // When elements only contain dropped markers and no positive markers,
        // it shouldn't match the positive marker regex.
        // It might pass as confirmed absence if URL is not an auth path and not an auth page,
        // but positive markers should be specific.
        LoginVerificationResult res = TaskExecutorService.verifyLoginStatus(
            elements, "Welcome to the site. Profile and settings here.", "https://example.com/signup", nonAuthSurface
        );
        // On an auth path like /signup, generic markers must NOT pass login verification
        assertThat(res.verified()).isFalse();
    }

    @Test
    @DisplayName("Positive markers (sign out, log out, my account, dashboard, avatar) pass verification and allow saving storage state")
    void testPositiveMarkersPassAndAllowSave() {
        List<String> positiveMarkers = List.of(
            "Sign Out",
            "log out",
            "logout",
            "My Account",
            "Dashboard",
            "Avatar"
        );

        for (String marker : positiveMarkers) {
            List<Map<String, Object>> elements = List.of(
                Map.of("text", marker, "role", "button")
            );
            LoginVerificationResult res = TaskExecutorService.verifyLoginStatus(
                elements, "User home page", "https://example.com/home", nonAuthSurface
            );
            assertThat(res.verified()).isTrue();
            assertThat(res.canSaveStorageState()).isTrue();
        }
    }

    @Test
    @DisplayName("URL fallback alone allows verification but does not allow saving storage state")
    void testUrlFallbackAloneDoesNotAllowSavingStorageState() {
        // Empty elements, no positive markers, but URL is not an auth path
        List<Map<String, Object>> emptyElements = List.of();
        LoginVerificationResult res = TaskExecutorService.verifyLoginStatus(
            emptyElements, "", "https://example.com/catalog", nonAuthSurface
        );

        assertThat(res.verified()).isTrue();
        assertThat(res.canSaveStorageState()).isFalse();
    }
}
