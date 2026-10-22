package com.doo;

import com.doo.service.TaskExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth Gate Options & Surface Detection Tests")
class AuthGateOptionsTest {

    @Test
    @DisplayName("Detects sign-up surface from URL and heading text")
    void testDetectSignupSurface() {
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/signup", "", List.of())).isTrue();
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/sign-up", "", List.of())).isTrue();
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/register", "", List.of())).isTrue();
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/join", "", List.of())).isTrue();
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/create-account", "", List.of())).isTrue();

        assertThat(TaskExecutorService.isSignupSurface("https://example.com/login", "Create your account today", List.of())).isTrue();
        assertThat(TaskExecutorService.isSignupSurface("https://example.com/account", "Welcome to the portal. Enter your password to log in.", List.of())).isFalse();
    }

    @Test
    @DisplayName("Finds log-in link on sign-up page")
    void testFindLoginLinkOnSignupPage() {
        List<Map<String, Object>> elements = List.of(
            Map.of("index", 1, "role", "button", "text", "Sign up with Google"),
            Map.of("index", 2, "role", "textbox", "label", "Email"),
            Map.of("index", 3, "role", "link", "text", "Already have an account? Sign in", "href", "/login")
        );

        Map<String, Object> link = TaskExecutorService.findLoginLinkOnSignupPage(elements);
        assertThat(link).isNotNull();
        assertThat(link.get("index")).isEqualTo(3);
    }

    @Test
    @DisplayName("Detects confirmation email / check your inbox state")
    void testDetectConfirmationEmailState() {
        assertThat(TaskExecutorService.isConfirmationEmailState("Please check your inbox to confirm your email.")).isTrue();
        assertThat(TaskExecutorService.isConfirmationEmailState("We have sent a magic link to your email address.")).isTrue();
        assertThat(TaskExecutorService.isConfirmationEmailState("Confirm your email by clicking the link.")).isTrue();
        assertThat(TaskExecutorService.isConfirmationEmailState("We've sent you a verification code.")).isTrue();

        assertThat(TaskExecutorService.isConfirmationEmailState("Enter your email and password to sign in.")).isFalse();
        assertThat(TaskExecutorService.isConfirmationEmailState("")).isFalse();
        assertThat(TaskExecutorService.isConfirmationEmailState(null)).isFalse();
    }
}
