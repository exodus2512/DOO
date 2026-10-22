package com.doo.service;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route Preference Service
 * Evaluates target domains before browser launch to prefer official routes:
 * 1. Public API or RSS/Atom feed
 * 2. Search API instead of scraping search engines
 * 3. robots.txt and site terms link checking (stops if disallowed)
 */
@Service
@Slf4j
public class RoutePreferenceService {

    public enum RouteType {
        OFFICIAL_API,
        SEARCH_API,
        RSS_FEED,
        BROWSER_ALLOWED,
        ROBOTS_DISALLOWED
    }

    @Data
    @Builder
    public static class RoutePreferenceResult {
        private boolean canProceed;
        private RouteType routeType;
        private String officialRouteUrl;
        private String stopReason;
        private String termsOfServiceUrl;
        private String description;
    }

    private final HttpClient httpClient;
    private final Map<String, RobotsRules> robotsCache = new HashMap<>();

    public RoutePreferenceService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public RoutePreferenceService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Evaluate the target URL and user request for route preference.
     */
    public RoutePreferenceResult evaluate(String targetUrl, String userRequest) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RouteType.BROWSER_ALLOWED)
                .description("No target URL provided — proceeding with agent defaults")
                .build();
        }

        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (Exception e) {
            return RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RouteType.BROWSER_ALLOWED)
                .description("Malformed target URL: " + targetUrl)
                .build();
        }

        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        String path = uri.getPath() != null && !uri.getPath().isEmpty() ? uri.getPath() : "/";

        // (b) Search API instead of scraping search engines
        if (isSearchEngineScrape(host, path)) {
            String query = extractSearchQuery(uri.getQuery());
            String searchApiUrl = "https://api.duckduckgo.com/?q=" + (query != null ? query : "") + "&format=json";
            log.info("RoutePreference: Search query on {} matched; preferring official Search API route", host);
            return RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RouteType.SEARCH_API)
                .officialRouteUrl(searchApiUrl)
                .termsOfServiceUrl(buildTermsUrl(uri))
                .description("Official search API preferred over scraping search engine HTML")
                .build();
        }

        // (a) Public API or RSS/feed autodiscovery
        Optional<String> publicApiRoute = detectPublicApi(host, path);
        if (publicApiRoute.isPresent()) {
            log.info("RoutePreference: Known official API found for {}: {}", host, publicApiRoute.get());
            return RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RouteType.OFFICIAL_API)
                .officialRouteUrl(publicApiRoute.get())
                .termsOfServiceUrl(buildTermsUrl(uri))
                .description("Official public API found for domain: " + host)
                .build();
        }

        Optional<String> rssRoute = detectRssFeed(host, path);
        if (rssRoute.isPresent()) {
            log.info("RoutePreference: Known RSS/feed found for {}: {}", host, rssRoute.get());
            return RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RouteType.RSS_FEED)
                .officialRouteUrl(rssRoute.get())
                .termsOfServiceUrl(buildTermsUrl(uri))
                .description("Official RSS/Atom feed found for domain: " + host)
                .build();
        }

        // (c) robots.txt check
        RobotsRules robots = fetchRobotsTxt(uri);
        if (robots != null && !robots.isAllowed(path)) {
            String stopReason = "Access disallowed by site's robots.txt: Disallow " + robots.getMatchedRule(path);
            log.warn("RoutePreference: URL {} blocked by robots.txt on {}", targetUrl, host);
            return RoutePreferenceResult.builder()
                .canProceed(false)
                .routeType(RouteType.ROBOTS_DISALLOWED)
                .stopReason(stopReason)
                .termsOfServiceUrl(buildTermsUrl(uri))
                .description(stopReason)
                .build();
        }

        return RoutePreferenceResult.builder()
            .canProceed(true)
            .routeType(RouteType.BROWSER_ALLOWED)
            .termsOfServiceUrl(buildTermsUrl(uri))
            .description("No direct API available; robots.txt allows access")
            .build();
    }

    /**
     * Checks if URL points to a search engine query page.
     */
    private boolean isSearchEngineScrape(String host, String path) {
        if (host.contains("google.") && (path.startsWith("/search") || path.startsWith("/url"))) return true;
        if (host.contains("bing.") && path.startsWith("/search")) return true;
        if (host.contains("duckduckgo.com") && (path.equals("/") || path.startsWith("/html"))) return true;
        if (host.contains("search.yahoo.com")) return true;
        return false;
    }

    private String extractSearchQuery(String rawQuery) {
        if (rawQuery == null) return "";
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && ("q".equalsIgnoreCase(kv[0]) || "query".equalsIgnoreCase(kv[0]))) {
                return kv[1];
            }
        }
        return "";
    }

    /**
     * Detect known public REST APIs.
     */
    public Optional<String> detectPublicApi(String host, String path) {
        if (host.contains("wikipedia.org")) {
            Matcher m = Pattern.compile("/wiki/([^#?]+)").matcher(path);
            if (m.find()) {
                String title = m.group(1);
                return Optional.of("https://" + host + "/api/rest_v1/page/summary/" + title);
            }
        }
        if (host.contains("reddit.com")) {
            if (path.startsWith("/r/") && !path.endsWith(".json")) {
                return Optional.of("https://www.reddit.com" + path + ".json");
            }
        }
        if (host.contains("news.ycombinator.com")) {
            return Optional.of("https://hacker-news.firebaseio.com/v0/topstories.json");
        }
        if (host.contains("github.com")) {
            Matcher m = Pattern.compile("^/([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)(?:/.*)?$").matcher(path);
            if (m.find()) {
                return Optional.of("https://api.github.com/repos/" + m.group(1));
            }
        }
        return Optional.empty();
    }

    public Optional<String> detectRssFeed(String host, String path) {
        if (path.endsWith("/feed") || path.endsWith("/rss") || path.endsWith("/atom.xml")) {
            return Optional.of("https://" + host + path);
        }
        return Optional.empty();
    }

    private String buildTermsUrl(URI uri) {
        if (uri.getHost() == null) return null;
        String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
        return scheme + "://" + uri.getHost() + "/terms";
    }

    /**
     * Fetch and parse robots.txt for the given origin.
     */
    public RobotsRules fetchRobotsTxt(URI uri) {
        if (uri.getHost() == null) return null;
        String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
        String origin = scheme + "://" + uri.getHost();

        if (robotsCache.containsKey(origin)) {
            return robotsCache.get(origin);
        }

        String robotsUrl = origin + "/robots.txt";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(robotsUrl))
                .header("User-Agent", "DOO-Agent/1.0 (+https://github.com/joshua-moses/DOO)")
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null) {
                RobotsRules rules = parseRobotsTxt(resp.body());
                robotsCache.put(origin, rules);
                return rules;
            }
        } catch (Exception e) {
            log.debug("Could not fetch robots.txt for {}: {}", origin, e.getMessage());
        }

        RobotsRules emptyRules = new RobotsRules(List.of(), List.of());
        robotsCache.put(origin, emptyRules);
        return emptyRules;
    }

    /**
     * Parses standard robots.txt lines for User-agent: * and User-agent: DOO.
     */
    public static RobotsRules parseRobotsTxt(String content) {
        List<String> disallows = new ArrayList<>();
        List<String> allows = new ArrayList<>();
        boolean inRelevantAgent = false;

        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            int commentIdx = line.indexOf('#');
            if (commentIdx != -1) {
                line = line.substring(0, commentIdx).trim();
            }
            if (line.isEmpty()) continue;

            String lower = line.toLowerCase();
            if (lower.startsWith("user-agent:")) {
                String agent = line.substring("user-agent:".length()).trim();
                inRelevantAgent = "*".equals(agent) || "doo".equalsIgnoreCase(agent) || "doo-agent".equalsIgnoreCase(agent);
            } else if (inRelevantAgent) {
                if (lower.startsWith("disallow:")) {
                    String rule = line.substring("disallow:".length()).trim();
                    if (!rule.isEmpty()) {
                        disallows.add(rule);
                    }
                } else if (lower.startsWith("allow:")) {
                    String rule = line.substring("allow:".length()).trim();
                    if (!rule.isEmpty()) {
                        allows.add(rule);
                    }
                }
            }
        }
        return new RobotsRules(disallows, allows);
    }

    public static class RobotsRules {
        private final List<String> disallows;
        private final List<String> allows;

        public RobotsRules(List<String> disallows, List<String> allows) {
            this.disallows = disallows != null ? disallows : List.of();
            this.allows = allows != null ? allows : List.of();
        }

        public boolean isAllowed(String path) {
            if (path == null) return true;
            // First check explicit allow rules
            for (String allow : allows) {
                if (matchesRule(allow, path)) {
                    return true;
                }
            }
            // Check disallow rules
            for (String disallow : disallows) {
                if (matchesRule(disallow, path)) {
                    return false;
                }
            }
            return true;
        }

        public String getMatchedRule(String path) {
            for (String disallow : disallows) {
                if (matchesRule(disallow, path)) {
                    return disallow;
                }
            }
            return "";
        }

        private boolean matchesRule(String rule, String path) {
            if (rule.equals("/")) return true;
            if (rule.endsWith("*")) {
                String prefix = rule.substring(0, rule.length() - 1);
                return path.startsWith(prefix);
            }
            return path.startsWith(rule);
        }

        public List<String> getDisallows() { return disallows; }
        public List<String> getAllows() { return allows; }
    }
}
