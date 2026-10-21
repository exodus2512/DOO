package com.doo;

import com.doo.security.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for URL validation security.
 */
class UrlValidatorTest {

    private UrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UrlValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.google.com",
        "https://en.wikipedia.org/wiki/Test",
        "http://example.com",
        "https://github.com/user/repo",
        "https://news.ycombinator.com"
    })
    @DisplayName("Valid public URLs are allowed")
    void validUrlsAllowed(String url) {
        assertThatNoException().isThrownBy(() -> validator.validate(url));
        assertThat(validator.isAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "file://C:/Windows/System32",
        "file:///home/user/.ssh/id_rsa"
    })
    @DisplayName("file:// URLs are blocked")
    void fileUrlsBlocked(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(SecurityException.class);
        assertThat(validator.isAllowed(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost",
        "http://localhost:8080",
        "http://localhost:3000/api"
    })
    @DisplayName("localhost is blocked")
    void localhostBlocked(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1",
        "http://127.0.0.1:8080",
        "http://127.1.1.1"
    })
    @DisplayName("Loopback IPs are blocked")
    void loopbackBlocked(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://192.168.1.1",
        "http://192.168.0.100",
        "http://10.0.0.1",
        "http://10.255.255.255",
        "http://172.16.0.1",
        "http://172.31.255.255"
    })
    @DisplayName("Private IP ranges are blocked")
    void privateIpsBlocked(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Cloud metadata endpoint is blocked")
    void cloudMetadataBlocked() {
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
            .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "data:text/html,<script>alert(1)</script>"
    })
    @DisplayName("Dangerous schemes are blocked")
    void dangerousSchemesBlocked(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Null URL throws")
    void nullUrlThrows() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Blank URL throws")
    void blankUrlThrows() {
        assertThatThrownBy(() -> validator.validate("  "))
            .isInstanceOf(SecurityException.class);
    }
}
