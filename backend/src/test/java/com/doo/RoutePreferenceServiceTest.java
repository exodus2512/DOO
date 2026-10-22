package com.doo;

import com.doo.service.RoutePreferenceService;
import com.doo.service.RoutePreferenceService.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Route Preference Service Tests")
class RoutePreferenceServiceTest {

    private final RoutePreferenceService service = new RoutePreferenceService();

    @Test
    @DisplayName("Detects official Wikipedia REST API for article URLs")
    void detectsWikipediaApi() {
        Optional<String> api = service.detectPublicApi("en.wikipedia.org", "/wiki/Alan_Turing");
        assertThat(api).isPresent();
        assertThat(api.get()).isEqualTo("https://en.wikipedia.org/api/rest_v1/page/summary/Alan_Turing");
    }

    @Test
    @DisplayName("Detects official Reddit JSON route")
    void detectsRedditJsonRoute() {
        Optional<String> api = service.detectPublicApi("reddit.com", "/r/technology");
        assertThat(api).isPresent();
        assertThat(api.get()).isEqualTo("https://www.reddit.com/r/technology.json");
    }

    @Test
    @DisplayName("Prefers search API route over scraping search engine HTML")
    void prefersSearchApiOverScraping() {
        RoutePreferenceResult result = service.evaluate("https://www.google.com/search?q=open+standards", "find open standards");
        assertThat(result.getRouteType()).isEqualTo(RouteType.SEARCH_API);
        assertThat(result.getOfficialRouteUrl()).contains("duckduckgo.com");
        assertThat(result.isCanProceed()).isTrue();
    }

    @Test
    @DisplayName("Parses robots.txt and disallows blocked path")
    void parsesRobotsTxtAndDisallows() {
        String robotsContent = """
            User-agent: *
            Disallow: /admin/
            Disallow: /private/
            Allow: /private/public-doc
            """;

        RobotsRules rules = RoutePreferenceService.parseRobotsTxt(robotsContent);

        assertThat(rules.isAllowed("/blog/post-1")).isTrue();
        assertThat(rules.isAllowed("/admin/users")).isFalse();
        assertThat(rules.isAllowed("/private/secret")).isFalse();
        assertThat(rules.isAllowed("/private/public-doc")).isTrue();
    }

    @Test
    @DisplayName("Honors User-agent: DOO specific rules in robots.txt")
    void honorsDooUserAgentRules() {
        String robotsContent = """
            User-agent: Googlebot
            Disallow: /search/
            
            User-agent: DOO
            Disallow: /api/crawler/
            """;

        RobotsRules rules = RoutePreferenceService.parseRobotsTxt(robotsContent);
        assertThat(rules.isAllowed("/api/crawler/data")).isFalse();
        assertThat(rules.isAllowed("/search/items")).isTrue();
    }

    @Test
    @DisplayName("Includes site terms link in route preference result")
    void includesTermsLink() {
        RoutePreferenceResult result = service.evaluate("https://example.org/about", "check info");
        assertThat(result.getTermsOfServiceUrl()).isEqualTo("https://example.org/terms");
    }
}
