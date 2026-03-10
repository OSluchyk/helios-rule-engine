package com.helios.ruleengine.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Gatling performance test for the Helios Rule Engine evaluation API.
 *
 * <p>Covers all evaluation endpoints:
 * <ul>
 *   <li>POST /api/v1/evaluate — single event (fast path)</li>
 *   <li>POST /api/v1/evaluate/trace — single event with tracing</li>
 *   <li>POST /api/v1/evaluate/batch — batch evaluation</li>
 * </ul>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Start the Helios service first:
 *   cd helios-service && mvn quarkus:dev
 *
 *   # Default simulation (ramp to 50 users over 30s, sustain 1 min):
 *   cd helios-gatling && mvn gatling:test
 *
 *   # Override target URL:
 *   mvn gatling:test -Dgatling.baseUrl=http://prod-host:8080
 *
 *   # Override load profile:
 *   mvn gatling:test -Dgatling.users=100 -Dgatling.rampDuration=60 -Dgatling.steadyDuration=120
 * </pre>
 */
public class RuleEvaluationSimulation extends Simulation {

    // ── Configuration (overridable via -D system properties) ──────────────────

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final int USERS = Integer.getInteger("gatling.users", 50);
    private static final int RAMP_SECONDS = Integer.getInteger("gatling.rampDuration", 30);
    private static final int STEADY_SECONDS = Integer.getInteger("gatling.steadyDuration", 60);

    // ── Event data pools ─────────────────────────────────────────────────────

    private static final String[] EVENT_TYPES = {"ORDER", "LOGIN", "PAYMENT", "REGISTRATION", "TRANSFER"};
    private static final String[] COUNTRIES = {"US", "GB", "DE", "FR", "JP", "AU", "CA", "BR", "IN", "XX"};
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "BRL", "INR"};
    private static final String[] CHANNELS = {"WEB", "MOBILE", "API", "POS", "ATM"};
    private static final String[] RISK_LEVELS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};

    // ── HTTP protocol ────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    // ── Feeders ──────────────────────────────────────────────────────────────

    /**
     * Generates random event JSON payloads with realistic attributes.
     * Some events are designed to match typical fraud-detection rules,
     * others will miss intentionally to test both code paths.
     */
    private static Iterator<Map<String, Object>> eventFeeder() {
        return Stream.generate(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            String eventId = "perf-" + UUID.randomUUID();
            String eventType = EVENT_TYPES[rng.nextInt(EVENT_TYPES.length)];
            int amount = rng.nextInt(1, 50_001);
            String country = COUNTRIES[rng.nextInt(COUNTRIES.length)];
            String currency = CURRENCIES[rng.nextInt(CURRENCIES.length)];
            String channel = CHANNELS[rng.nextInt(CHANNELS.length)];
            int riskScore = rng.nextInt(0, 101);
            int txnCount = rng.nextInt(0, 201);

            String body = String.format("""
                    {
                      "eventId": "%s",
                      "eventType": "%s",
                      "attributes": {
                        "AMOUNT": %d,
                        "COUNTRY": "%s",
                        "CURRENCY": "%s",
                        "CHANNEL": "%s",
                        "USER_RISK_SCORE": %d,
                        "TRANSACTION_COUNT": %d,
                        "type": "%s"
                      }
                    }""", eventId, eventType, amount, country, currency, channel, riskScore, txnCount,
                    eventType.toLowerCase());

            return Map.<String, Object>of("eventBody", body, "eventId", eventId);
        }).iterator();
    }

    /**
     * Generates batch payloads of 10-50 events each.
     */
    private static Iterator<Map<String, Object>> batchFeeder() {
        return Stream.generate(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int batchSize = rng.nextInt(10, 51);
            String events = IntStream.range(0, batchSize)
                    .mapToObj(i -> {
                        String eid = "batch-" + UUID.randomUUID();
                        int amount = rng.nextInt(1, 50_001);
                        String country = COUNTRIES[rng.nextInt(COUNTRIES.length)];
                        int riskScore = rng.nextInt(0, 101);
                        return String.format("""
                                {
                                  "eventId": "%s",
                                  "eventType": "ORDER",
                                  "attributes": {
                                    "AMOUNT": %d,
                                    "COUNTRY": "%s",
                                    "USER_RISK_SCORE": %d,
                                    "type": "order"
                                  }
                                }""", eid, amount, country, riskScore);
                    })
                    .collect(Collectors.joining(",\n"));

            return Map.<String, Object>of("batchBody", "[" + events + "]", "batchSize", batchSize);
        }).iterator();
    }

    // ── Scenarios ────────────────────────────────────────────────────────────

    /**
     * Fast-path single event evaluation — the primary hot path.
     */
    private final ScenarioBuilder singleEvaluate = scenario("Single Evaluate")
            .feed(eventFeeder())
            .exec(
                    http("POST /evaluate")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.eventId").exists())
                            .check(jsonPath("$.evaluationTimeNanos").ofLong().saveAs("evalTime"))
            );

    /**
     * Evaluation with full tracing — measures overhead.
     */
    private final ScenarioBuilder traceEvaluate = scenario("Trace Evaluate")
            .feed(eventFeeder())
            .exec(
                    http("POST /evaluate/trace")
                            .post("/api/v1/evaluate/trace")
                            .queryParam("level", "FULL")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.match_result.eventId").exists())
                            .check(jsonPath("$.trace").exists())
            );

    /**
     * Batch evaluation — measures throughput under bulk load.
     */
    private final ScenarioBuilder batchEvaluate = scenario("Batch Evaluate")
            .feed(batchFeeder())
            .exec(
                    http("POST /evaluate/batch")
                            .post("/api/v1/evaluate/batch")
                            .body(StringBody("#{batchBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.stats.totalEvents").ofInt().gte(1))
            );

    /**
     * Mixed workload — realistic production traffic pattern:
     * 70% single evaluate, 20% trace, 10% batch.
     */
    private final ScenarioBuilder mixedWorkload = scenario("Mixed Workload")
            .randomSwitch().on(
                    new Choice.WithWeight(70, feed(eventFeeder()).exec(
                            http("POST /evaluate")
                                    .post("/api/v1/evaluate")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(20, feed(eventFeeder()).exec(
                            http("POST /evaluate/trace")
                                    .post("/api/v1/evaluate/trace")
                                    .queryParam("level", "BASIC")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(10, feed(batchFeeder()).exec(
                            http("POST /evaluate/batch")
                                    .post("/api/v1/evaluate/batch")
                                    .body(StringBody("#{batchBody}"))
                                    .check(status().is(200))
                    ))
            );

    // ── Load profile ─────────────────────────────────────────────────────────

    {
        setUp(
                // Warm-up: single evaluate ramp
                singleEvaluate.injectOpen(
                        rampUsers(USERS).during(RAMP_SECONDS)
                ),
                // Sustained mixed traffic
                mixedWorkload.injectOpen(
                        nothingFor(RAMP_SECONDS),  // wait for warm-up
                        constantUsersPerSec(USERS).during(STEADY_SECONDS)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile(50.0).lt(50),   // p50 < 50ms
                 global().responseTime().percentile(95.0).lt(200),  // p95 < 200ms
                 global().responseTime().percentile(99.0).lt(500),  // p99 < 500ms
                 global().successfulRequests().percent().gt(99.0)   // >99% success
         );
    }
}
