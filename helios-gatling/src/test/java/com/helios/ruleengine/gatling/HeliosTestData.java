package com.helios.ruleengine.gatling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Shared test data for Gatling performance tests.
 *
 * <p>Defines a ruleset of 20 rules with diverse conditions and operators,
 * plus event feeders that produce a ~50/50 match vs. non-match ratio.
 *
 * <h3>Rule breakdown</h3>
 * <ul>
 *   <li>5 simple rules (1–2 conditions)</li>
 *   <li>5 medium rules (3 conditions)</li>
 *   <li>10 complex rules (4–5 conditions) — 50% of the ruleset</li>
 * </ul>
 */
public final class HeliosTestData {

    private HeliosTestData() {}

    // ═════════════════════════════════════════════════════════════════════════
    // RULE DEFINITIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * All 20 rules as JSON strings, ready to POST to /api/v1/rules.
     */
    static final List<String> RULES = List.of(

            // ── Simple rules (1–2 conditions) ────────────────────────────────

            """
            {
              "rule_code": "PERF_HIGH_AMOUNT",
              "description": "Flag transactions above 40k",
              "priority": 10, "enabled": true,
              "conditions": [
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 40000}
              ]
            }""",

            """
            {
              "rule_code": "PERF_BRAZIL_TXN",
              "description": "All Brazil transactions",
              "priority": 20, "enabled": true,
              "conditions": [
                {"field": "COUNTRY", "operator": "EQUAL_TO", "value": "BR"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_ATM_JPY",
              "description": "ATM withdrawals in JPY",
              "priority": 30, "enabled": true,
              "conditions": [
                {"field": "CHANNEL", "operator": "EQUAL_TO", "value": "ATM"},
                {"field": "CURRENCY", "operator": "EQUAL_TO", "value": "JPY"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_CRITICAL_RISK",
              "description": "Critical risk level flag",
              "priority": 40, "enabled": true,
              "conditions": [
                {"field": "RISK_LEVEL", "operator": "EQUAL_TO", "value": "CRITICAL"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_FLAGGED_ACCOUNT",
              "description": "Flagged account activity",
              "priority": 50, "enabled": true,
              "conditions": [
                {"field": "ACCOUNT_STATUS", "operator": "EQUAL_TO", "value": "FLAGGED"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 100}
              ]
            }""",

            // ── Medium rules (3 conditions) ──────────────────────────────────

            """
            {
              "rule_code": "PERF_RISKY_ORDER",
              "description": "High-risk high-value orders",
              "priority": 100, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "ORDER"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 10000},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 70}
              ]
            }""",

            """
            {
              "rule_code": "PERF_US_PAYMENT",
              "description": "US payments above 5k",
              "priority": 110, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "PAYMENT"},
                {"field": "COUNTRY", "operator": "EQUAL_TO", "value": "US"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 5000}
              ]
            }""",

            """
            {
              "rule_code": "PERF_WEB_LOGIN_RISK",
              "description": "Risky web logins",
              "priority": 120, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "LOGIN"},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 80},
                {"field": "CHANNEL", "operator": "EQUAL_TO", "value": "WEB"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_EUR_HIGH_VALUE",
              "description": "High-value EUR in EU",
              "priority": 130, "enabled": true,
              "conditions": [
                {"field": "CURRENCY", "operator": "EQUAL_TO", "value": "EUR"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 8000},
                {"field": "COUNTRY", "operator": "IS_ANY_OF", "value": ["DE", "FR", "IT", "ES"]}
              ]
            }""",

            """
            {
              "rule_code": "PERF_HIGH_TRANSFER",
              "description": "Large transfers from high-risk",
              "priority": 140, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "TRANSFER"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 15000},
                {"field": "RISK_LEVEL", "operator": "EQUAL_TO", "value": "HIGH"}
              ]
            }""",

            // ── Complex rules (4+ conditions) ────────────────────────────────

            """
            {
              "rule_code": "PERF_FRAUD_ORDER_1",
              "description": "Fraud pattern: high-value orders from key markets",
              "priority": 200, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "ORDER"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 20000},
                {"field": "COUNTRY", "operator": "IS_ANY_OF", "value": ["US", "GB", "DE"]},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 60}
              ]
            }""",

            """
            {
              "rule_code": "PERF_FRAUD_PAYMENT_1",
              "description": "Fraud pattern: mid-range digital payments",
              "priority": 210, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "PAYMENT"},
                {"field": "AMOUNT", "operator": "BETWEEN", "value": [5000, 50000]},
                {"field": "CHANNEL", "operator": "IS_ANY_OF", "value": ["WEB", "MOBILE"]},
                {"field": "COUNTRY", "operator": "NOT_EQUAL_TO", "value": "XX"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_FRAUD_TRANSFER_1",
              "description": "Fraud pattern: API transfers in major currencies",
              "priority": 220, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "TRANSFER"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 10000},
                {"field": "CURRENCY", "operator": "IS_ANY_OF", "value": ["USD", "EUR"]},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 50},
                {"field": "CHANNEL", "operator": "EQUAL_TO", "value": "API"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_JP_MOBILE_ORDER",
              "description": "Japan mobile orders with elevated risk",
              "priority": 230, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "ORDER"},
                {"field": "COUNTRY", "operator": "EQUAL_TO", "value": "JP"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 12000},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 40},
                {"field": "CHANNEL", "operator": "EQUAL_TO", "value": "MOBILE"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_PREMIUM_PAYMENT",
              "description": "Premium-tier USD payments",
              "priority": 240, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "PAYMENT"},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 25000},
                {"field": "RISK_LEVEL", "operator": "EQUAL_TO", "value": "HIGH"},
                {"field": "TIER", "operator": "IS_ANY_OF", "value": ["GOLD", "PLATINUM"]},
                {"field": "CURRENCY", "operator": "EQUAL_TO", "value": "USD"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_AML_SCREEN_1",
              "description": "AML screening: high-frequency high-value",
              "priority": 250, "enabled": true,
              "conditions": [
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 8000},
                {"field": "COUNTRY", "operator": "IS_ANY_OF", "value": ["US", "GB", "CA"]},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 45},
                {"field": "TRANSACTION_COUNT", "operator": "GREATER_THAN", "value": 20},
                {"field": "CHANNEL", "operator": "IS_ANY_OF", "value": ["WEB", "MOBILE"]}
              ]
            }""",

            """
            {
              "rule_code": "PERF_AML_SCREEN_2",
              "description": "AML screening: suspicious cross-border transfers",
              "priority": 260, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "TRANSFER"},
                {"field": "AMOUNT", "operator": "BETWEEN", "value": [2000, 100000]},
                {"field": "CURRENCY", "operator": "IS_ANY_OF", "value": ["USD", "EUR", "GBP"]},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 35},
                {"field": "COUNTRY", "operator": "IS_NONE_OF", "value": ["XX", "ZZ"]}
              ]
            }""",

            """
            {
              "rule_code": "PERF_COMPLIANCE_WEB",
              "description": "Compliance: US web orders/payments above threshold",
              "priority": 270, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "IS_ANY_OF", "value": ["ORDER", "PAYMENT"]},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 15000},
                {"field": "COUNTRY", "operator": "EQUAL_TO", "value": "US"},
                {"field": "CHANNEL", "operator": "EQUAL_TO", "value": "WEB"},
                {"field": "TIER", "operator": "NOT_EQUAL_TO", "value": "BLOCKED"}
              ]
            }""",

            """
            {
              "rule_code": "PERF_VELOCITY_CHECK",
              "description": "Velocity: high-frequency valuable transactions",
              "priority": 280, "enabled": true,
              "conditions": [
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 10000},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 55},
                {"field": "TRANSACTION_COUNT", "operator": "GREATER_THAN", "value": 30},
                {"field": "CHANNEL", "operator": "IS_ANY_OF", "value": ["WEB", "API"]},
                {"field": "CURRENCY", "operator": "IS_ANY_OF", "value": ["USD", "EUR"]}
              ]
            }""",

            """
            {
              "rule_code": "PERF_VELOCITY_ORDER",
              "description": "Velocity: frequent orders from anglophone markets",
              "priority": 290, "enabled": true,
              "conditions": [
                {"field": "EVENT_TYPE", "operator": "EQUAL_TO", "value": "ORDER"},
                {"field": "TRANSACTION_COUNT", "operator": "GREATER_THAN", "value": 40},
                {"field": "AMOUNT", "operator": "GREATER_THAN", "value": 5000},
                {"field": "RISK_SCORE", "operator": "GREATER_THAN", "value": 35},
                {"field": "COUNTRY", "operator": "IS_ANY_OF", "value": ["US", "GB", "AU", "CA"]}
              ]
            }"""
    );

    // ═════════════════════════════════════════════════════════════════════════
    // SETUP: import rules + compile
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Cleans all existing rules, imports the test ruleset, and triggers compilation.
     * Intended to be called from {@code Simulation.before()}.
     *
     * @param baseUrl e.g. "http://localhost:8080"
     */
    static void seedRulesAndCompile(String baseUrl) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Helios Gatling — Setting up " + RULES.size() + " perf-test rules       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        try {
            // ── Step 1: Delete ALL existing rules ────────────────────────────
            System.out.println("  [1/3] Cleaning existing rules...");
            deleteAllRules(client, baseUrl);

            // ── Step 2: Create our test rules ────────────────────────────────
            System.out.println("  [2/3] Creating " + RULES.size() + " test rules...");
            int created = 0, failed = 0;
            for (String ruleJson : RULES) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/rules"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(ruleJson))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 201) {
                    created++;
                } else {
                    failed++;
                    // Extract rule_code from JSON for better logging
                    String code = extractRuleCode(ruleJson);
                    System.err.printf("    ✗ FAILED %s (HTTP %d): %s%n", code, resp.statusCode(), resp.body());
                }
            }
            System.out.printf("    → Created: %d, Failed: %d%n", created, failed);

            // ── Step 3: Trigger synchronous compilation ──────────────────────
            System.out.println("  [3/3] Triggering compilation...");
            HttpRequest compileReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/compilation/compile-from-db"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> compileResp = client.send(compileReq, HttpResponse.BodyHandlers.ofString());
            if (compileResp.statusCode() == 200) {
                System.out.println("    → Compilation OK: " + compileResp.body());
            } else {
                System.err.println("    ✗ Compilation failed (" + compileResp.statusCode() + "): " + compileResp.body());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to seed rules — is the Helios service running at " + baseUrl + "?", e);
        }

        System.out.println("  ✓ Setup complete\n");
    }

    /**
     * Fetches all existing rules and deletes them via batch-delete.
     */
    private static void deleteAllRules(HttpClient client, String baseUrl) throws Exception {
        // GET /rules → collect all rule codes
        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/rules"))
                .GET()
                .build();
        HttpResponse<String> listResp = client.send(listReq, HttpResponse.BodyHandlers.ofString());

        if (listResp.statusCode() != 200) {
            System.out.println("    → No existing rules (or endpoint unavailable)");
            return;
        }

        // Parse rule codes from JSON array — lightweight regex instead of Jackson dependency
        List<String> ruleCodes = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"rule_code\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(listResp.body());
        while (m.find()) {
            ruleCodes.add(m.group(1));
        }

        if (ruleCodes.isEmpty()) {
            System.out.println("    → No existing rules to clean");
            return;
        }

        System.out.printf("    → Found %d existing rules, deleting...%n", ruleCodes.size());

        // POST /rules/batch-delete
        StringJoiner codesJson = new StringJoiner("\",\"", "[\"", "\"]");
        for (String code : ruleCodes) {
            codesJson.add(code);
        }
        String batchDeleteBody = "{\"ruleCodes\":" + codesJson + "}";

        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/rules/batch-delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(batchDeleteBody))
                .build();
        HttpResponse<String> deleteResp = client.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("    → Batch delete (HTTP %d): %s%n", deleteResp.statusCode(), deleteResp.body());
    }

    /**
     * Extract rule_code from a JSON rule string for logging.
     */
    private static String extractRuleCode(String ruleJson) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"rule_code\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(ruleJson);
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EVENT FEEDERS
    // ═════════════════════════════════════════════════════════════════════════

    private static final String[] EVENT_TYPES = {"ORDER", "PAYMENT", "TRANSFER", "LOGIN", "REGISTRATION"};
    private static final String[] MATCH_COUNTRIES = {"US", "GB", "DE", "FR", "JP", "BR", "CA", "AU", "IT", "ES"};
    // Large pool of countries that appear in NO rule's conditions
    private static final String[] MISS_COUNTRIES = {
            "XX", "ZZ", "QQ", "AF", "AL", "DZ", "AD", "AO", "AG", "AM", "AZ", "BS", "BH", "BD",
            "BB", "BY", "BZ", "BJ", "BT", "BO", "BA", "BW", "BN", "BF", "BI", "KH", "CM", "CV",
            "CF", "TD", "CL", "CO", "KM", "CG", "CR", "HR", "CU", "CY", "CZ", "DK", "DJ", "DM",
            "DO", "EC", "EG", "SV", "GQ", "ER", "EE", "SZ", "ET", "FJ", "FI", "GA", "GM", "GE",
            "GH", "GR", "GD", "GT", "GN", "GW", "GY", "HT", "HN", "HU", "IS", "IN", "ID", "IR",
            "IQ", "IE", "IL", "JM", "JO", "KZ", "KE", "KI", "KW", "KG", "LA", "LV", "LB", "LS"
    };
    private static final String[] CHANNELS = {"WEB", "MOBILE", "API", "POS", "ATM"};
    // Extended currency list — only USD/EUR/GBP appear in rules
    private static final String[] MISS_CURRENCIES = {
            "CAD", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN",
            "TRY", "RUB", "INR", "IDR", "MYR", "PHP", "THB", "VND", "KRW", "TWD",
            "SGD", "HKD", "NZD", "MXN", "ARS", "COP", "PEN", "CLP", "ZAR", "NGN"
    };
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "BRL"};
    private static final String[] TIERS = {"STANDARD", "GOLD", "PLATINUM", "BLOCKED"};
    private static final String[] RISK_LEVELS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
    // Extra noise attribute values to inflate cache key space
    private static final String[] DEVICE_TYPES = {
            "DESKTOP", "MOBILE_IOS", "MOBILE_ANDROID", "TABLET", "SMART_TV", "WEARABLE", "UNKNOWN"
    };
    private static final String[] IP_RANGES = {
            "10.0.", "172.16.", "192.168.", "203.0.", "198.51.", "100.64.", "169.254."
    };

    /**
     * Balanced feeder: alternates between matching and non-matching events (~50/50).
     */
    static Iterator<Map<String, Object>> balancedEventFeeder() {
        return Stream.generate(new java.util.function.Supplier<Map<String, Object>>() {
            private boolean nextIsMatch = true;

            @Override
            public Map<String, Object> get() {
                boolean match = nextIsMatch;
                nextIsMatch = !nextIsMatch;
                String body = match ? randomMatchingEvent() : randomNonMatchingEvent();
                return Map.of("eventBody", body);
            }
        }).iterator();
    }

    /**
     * Feeder that only generates matching events (for targeted throughput tests).
     */
    static Iterator<Map<String, Object>> matchingEventFeeder() {
        return Stream.generate(() -> Map.<String, Object>of("eventBody", randomMatchingEvent())).iterator();
    }

    /**
     * Feeder that only generates non-matching events (for miss-path tests).
     */
    static Iterator<Map<String, Object>> nonMatchingEventFeeder() {
        return Stream.generate(() -> Map.<String, Object>of("eventBody", randomNonMatchingEvent())).iterator();
    }

    /**
     * Feeder producing random batch payloads with ~50% match ratio inside each batch.
     */
    static Iterator<Map<String, Object>> balancedBatchFeeder() {
        return Stream.generate(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int size = rng.nextInt(10, 51);
            StringJoiner sj = new StringJoiner(",\n", "[", "]");
            for (int i = 0; i < size; i++) {
                sj.add(i % 2 == 0 ? randomMatchingEvent() : randomNonMatchingEvent());
            }
            return Map.<String, Object>of("batchBody", sj.toString(), "batchSize", size);
        }).iterator();
    }

    // ─── Matching event generators ───────────────────────────────────────────
    //
    // 10 templates — each designed to satisfy at least one rule.
    // Templates 1–5 target simple/medium rules, 6–10 target complex (>3 cond.)

    @FunctionalInterface
    private interface EventTemplate {
        String generate(ThreadLocalRandom rng);
    }

    private static final List<EventTemplate> MATCH_TEMPLATES = List.of(

            // 1. Hits PERF_HIGH_AMOUNT (simple) + possibly PERF_FRAUD_ORDER_1 (complex)
            rng -> event(rng,
                    "ORDER",
                    rng.nextInt(41000, 500000),    // AMOUNT > 40000 — wide range
                    pick(rng, "US", "GB", "DE"),
                    pick(rng, CURRENCIES),
                    pick(rng, "WEB", "MOBILE", "API"),
                    rng.nextInt(61, 100),           // high RISK_SCORE
                    rng.nextInt(1, 200),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "VERIFIED", "FLAGGED")),

            // 2. Hits PERF_BRAZIL_TXN (simple)
            rng -> event(rng,
                    pick(rng, EVENT_TYPES),
                    rng.nextInt(1, 200000),         // any amount — rule only checks COUNTRY
                    "BR",                           // COUNTRY == BR
                    pick(rng, CURRENCIES),
                    pick(rng, CHANNELS),
                    rng.nextInt(0, 100),
                    rng.nextInt(0, 100),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "VERIFIED", "PENDING")),

            // 3. Hits PERF_ATM_JPY (simple, 2 cond.)
            rng -> event(rng,
                    pick(rng, EVENT_TYPES),
                    rng.nextInt(1, 500000),         // any amount
                    pick(rng, MATCH_COUNTRIES),
                    "JPY",                          // CURRENCY == JPY
                    "ATM",                           // CHANNEL == ATM
                    rng.nextInt(0, 100),
                    rng.nextInt(0, 100),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "VERIFIED")),

            // 4. Hits PERF_RISKY_ORDER (3 cond.) — ORDER + AMOUNT > 10k + RISK_SCORE > 70
            rng -> event(rng,
                    "ORDER",
                    rng.nextInt(11000, 300000),
                    pick(rng, MATCH_COUNTRIES),
                    pick(rng, CURRENCIES),
                    pick(rng, CHANNELS),
                    rng.nextInt(72, 100),
                    rng.nextInt(0, 200),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "PENDING", "VERIFIED")),

            // 5. Hits PERF_WEB_LOGIN_RISK (3 cond.) — LOGIN + RISK_SCORE > 80 + WEB
            rng -> event(rng,
                    "LOGIN",
                    rng.nextInt(0, 100000),
                    pick(rng, MATCH_COUNTRIES),
                    pick(rng, CURRENCIES),
                    "WEB",
                    rng.nextInt(82, 100),
                    rng.nextInt(0, 500),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "FLAGGED", "VERIFIED")),

            // 6. Hits PERF_FRAUD_TRANSFER_1 (5 cond.) — TRANSFER + AMOUNT > 10k + USD/EUR + RISK > 50 + API
            rng -> event(rng,
                    "TRANSFER",
                    rng.nextInt(11000, 500000),
                    pick(rng, MATCH_COUNTRIES),
                    pick(rng, "USD", "EUR"),
                    "API",
                    rng.nextInt(52, 100),
                    rng.nextInt(0, 200),
                    pick(rng, TIERS),
                    pick(rng, "HIGH", "CRITICAL", "MEDIUM"),
                    pick(rng, "ACTIVE", "VERIFIED")),

            // 7. Hits PERF_JP_MOBILE_ORDER (5 cond.) — ORDER + JP + AMOUNT > 12k + RISK > 40 + MOBILE
            rng -> event(rng,
                    "ORDER",
                    rng.nextInt(13000, 200000),
                    "JP",
                    pick(rng, CURRENCIES),
                    "MOBILE",
                    rng.nextInt(42, 100),
                    rng.nextInt(0, 200),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "VERIFIED")),

            // 8. Hits PERF_PREMIUM_PAYMENT (5 cond.) — PAYMENT + AMOUNT > 25k + HIGH + GOLD/PLAT + USD
            rng -> event(rng,
                    "PAYMENT",
                    rng.nextInt(26000, 500000),
                    pick(rng, MATCH_COUNTRIES),
                    "USD",
                    pick(rng, CHANNELS),
                    rng.nextInt(0, 100),
                    rng.nextInt(0, 200),
                    pick(rng, "GOLD", "PLATINUM"),
                    "HIGH",
                    pick(rng, "ACTIVE", "VERIFIED", "PENDING")),

            // 9. Hits PERF_AML_SCREEN_1 (5 cond.) — AMOUNT > 8k + US/GB/CA + RISK > 45 + TXN_COUNT > 20 + WEB/MOBILE
            rng -> event(rng,
                    pick(rng, EVENT_TYPES),
                    rng.nextInt(9000, 500000),
                    pick(rng, "US", "GB", "CA"),
                    pick(rng, CURRENCIES),
                    pick(rng, "WEB", "MOBILE"),
                    rng.nextInt(47, 100),
                    rng.nextInt(22, 500),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "VERIFIED")),

            // 10. Hits PERF_VELOCITY_ORDER (5 cond.) — ORDER + TXN_COUNT > 40 + AMOUNT > 5k + RISK > 35 + US/GB/AU/CA
            rng -> event(rng,
                    "ORDER",
                    rng.nextInt(6000, 300000),
                    pick(rng, "US", "GB", "AU", "CA"),
                    pick(rng, CURRENCIES),
                    pick(rng, CHANNELS),
                    rng.nextInt(37, 100),
                    rng.nextInt(42, 500),
                    pick(rng, TIERS),
                    pick(rng, RISK_LEVELS),
                    pick(rng, "ACTIVE", "PENDING", "VERIFIED"))
    );

    private static String randomMatchingEvent() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return MATCH_TEMPLATES.get(rng.nextInt(MATCH_TEMPLATES.size())).generate(rng);
    }

    /**
     * Generates diverse non-matching events. Every call randomizes ALL fields to
     * maximize cache-key diversity while staying below rule thresholds.
     *
     * <p>Key constraints to avoid matching any rule:
     * <ul>
     *   <li>AMOUNT &lt; 90 (below all ≥100 thresholds)</li>
     *   <li>COUNTRY from a large pool of countries absent from any rule</li>
     *   <li>RISK_SCORE &lt; 8 (below all thresholds ≥35)</li>
     *   <li>RISK_LEVEL = "LOW" (not HIGH/CRITICAL)</li>
     *   <li>TIER = "BLOCKED" (excluded by NOT_EQUAL_TO filters)</li>
     *   <li>ACCOUNT_STATUS ≠ "FLAGGED"</li>
     * </ul>
     *
     * <p>All other fields are fully randomized for maximum diversity.
     */
    private static String randomNonMatchingEvent() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Vary event type — "REGISTRATION" plus others; constraints on amount/risk
        // ensure we don't match even ORDER/PAYMENT/TRANSFER/LOGIN rules
        String eventType = pick(rng, EVENT_TYPES);
        return event(rng,
                eventType,
                rng.nextInt(1, 90),                // AMOUNT below all thresholds
                pick(rng, MISS_COUNTRIES),          // Large pool of 80+ countries not in rules
                pick(rng, MISS_CURRENCIES),         // 30 currencies not targeted by rules
                pick(rng, CHANNELS),                // Fully random channel
                rng.nextInt(0, 8),                  // RISK_SCORE below all thresholds
                rng.nextInt(0, 5),                  // TRANSACTION_COUNT below all thresholds
                "BLOCKED",                          // Excluded by NOT_EQUAL_TO
                "LOW",                              // Not HIGH/CRITICAL
                pick(rng, "NORMAL", "VERIFIED", "PENDING", "REVIEW"));  // Not FLAGGED
    }

    // ─── Event JSON builder ──────────────────────────────────────────────────

    private static String event(ThreadLocalRandom rng,
                                String eventType, int amount, String country, String currency,
                                String channel, int riskScore, int txnCount,
                                String tier, String riskLevel, String accountStatus) {
        // Add random noise attributes to inflate cache key diversity
        String deviceType = pick(rng, DEVICE_TYPES);
        String ipPrefix = pick(rng, IP_RANGES);
        String sourceIp = ipPrefix + rng.nextInt(1, 255) + "." + rng.nextInt(1, 255);
        int sessionAge = rng.nextInt(0, 86400);          // seconds in a day
        int loginAttempts = rng.nextInt(0, 20);
        String merchantId = "MER-" + rng.nextInt(10000, 99999);
        long timestamp = System.currentTimeMillis() - rng.nextLong(0, 86400000); // last 24h

        return String.format("""
                {
                  "eventId": "perf-%s",
                  "eventType": "%s",
                  "attributes": {
                    "EVENT_TYPE": "%s",
                    "AMOUNT": %d,
                    "COUNTRY": "%s",
                    "CURRENCY": "%s",
                    "CHANNEL": "%s",
                    "RISK_SCORE": %d,
                    "TRANSACTION_COUNT": %d,
                    "TIER": "%s",
                    "RISK_LEVEL": "%s",
                    "ACCOUNT_STATUS": "%s",
                    "DEVICE_TYPE": "%s",
                    "SOURCE_IP": "%s",
                    "SESSION_AGE_SEC": %d,
                    "LOGIN_ATTEMPTS": %d,
                    "MERCHANT_ID": "%s",
                    "EVENT_TIMESTAMP": %d
                  }
                }""",
                UUID.randomUUID(), eventType,
                eventType, amount, country, currency, channel,
                riskScore, txnCount, tier, riskLevel, accountStatus,
                deviceType, sourceIp, sessionAge, loginAttempts, merchantId, timestamp);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String pick(ThreadLocalRandom rng, String... values) {
        return values[rng.nextInt(values.length)];
    }
}
