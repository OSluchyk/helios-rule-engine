package com.helios.ruleengine.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Gatling performance test for the Helios Rule Engine evaluation API.
 *
 * <p>Imports a diverse ruleset of 20 rules (simple, medium, complex) and drives
 * traffic with a ~50/50 match vs. non-match event ratio.
 *
 * <h3>Ruleset (seeded via API at startup)</h3>
 * <ul>
 *   <li>5 simple rules  (1–2 conditions: EQUAL_TO, GREATER_THAN)</li>
 *   <li>5 medium rules  (3 conditions: adds IS_ANY_OF)</li>
 *   <li>10 complex rules (4–5 conditions: BETWEEN, IS_NONE_OF, NOT_EQUAL_TO, etc.)</li>
 * </ul>
 *
 * <h3>Endpoints covered</h3>
 * <ul>
 *   <li>{@code POST /api/v1/evaluate} — single event (fast path)</li>
 *   <li>{@code POST /api/v1/evaluate/trace} — single event with tracing (BASIC + FULL)</li>
 *   <li>{@code POST /api/v1/evaluate/batch} — batch evaluation</li>
 * </ul>
 *
 * <h3>Running</h3>
 * <pre>
 *   # Start the Helios service first:
 *   cd helios-service &amp;&amp; mvn quarkus:dev
 *
 *   # Default simulation (ramp to 50 users over 30s, sustain 1 min):
 *   cd helios-gatling &amp;&amp; mvn gatling:test
 *
 *   # Override target URL:
 *   mvn gatling:test -Dgatling.baseUrl=http://prod-host:8080
 *
 *   # Override load profile:
 *   mvn gatling:test -Dgatling.users=100 -Dgatling.rampDuration=60 -Dgatling.steadyDuration=120
 * </pre>
 */
public class RuleEvaluationSimulation extends Simulation {

    // ── Configuration (overridable via -D system properties) ─────────────────

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final int USERS = Integer.getInteger("gatling.users", 50);
    private static final int RAMP_SECONDS = Integer.getInteger("gatling.rampDuration", 30);
    private static final int STEADY_SECONDS = Integer.getInteger("gatling.steadyDuration", 60);

    // ── HTTP protocol ────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    // ── Setup: seed rules before test ────────────────────────────────────────

    @Override
    public void before() {
        HeliosTestData.seedRulesAndCompile(BASE_URL);
    }

    // ── Scenarios ────────────────────────────────────────────────────────────

    /**
     * Balanced single-event evaluation.
     * ~50% of events match at least one rule, ~50% miss all rules.
     */
    private final ScenarioBuilder singleEvaluate = scenario("Single Evaluate (balanced)")
            .feed(HeliosTestData.balancedEventFeeder())
            .exec(
                    http("POST /evaluate")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.eventId").exists())
                            .check(jsonPath("$.evaluationTimeNanos").ofLong().saveAs("evalTime"))
            );

    /**
     * Matching-only evaluation — stress-tests the hot path where rules fire.
     */
    private final ScenarioBuilder matchOnlyEvaluate = scenario("Single Evaluate (match-only)")
            .feed(HeliosTestData.matchingEventFeeder())
            .exec(
                    http("POST /evaluate (match)")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.matchedRules").exists())
            );

    /**
     * Miss-only evaluation — tests the early-exit / skip path.
     */
    private final ScenarioBuilder missOnlyEvaluate = scenario("Single Evaluate (miss-only)")
            .feed(HeliosTestData.nonMatchingEventFeeder())
            .exec(
                    http("POST /evaluate (miss)")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.matchedRules").exists())
            );

    /**
     * Evaluation with full tracing — measures overhead.
     */
    private final ScenarioBuilder traceFullEvaluate = scenario("Trace Evaluate (FULL)")
            .feed(HeliosTestData.balancedEventFeeder())
            .exec(
                    http("POST /evaluate/trace (FULL)")
                            .post("/api/v1/evaluate/trace")
                            .queryParam("level", "FULL")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.match_result.eventId").exists())
                            .check(jsonPath("$.trace").exists())
            );

    /**
     * Evaluation with BASIC tracing — lower overhead comparison.
     */
    private final ScenarioBuilder traceBasicEvaluate = scenario("Trace Evaluate (BASIC)")
            .feed(HeliosTestData.balancedEventFeeder())
            .exec(
                    http("POST /evaluate/trace (BASIC)")
                            .post("/api/v1/evaluate/trace")
                            .queryParam("level", "BASIC")
                            .body(StringBody("#{eventBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.match_result.eventId").exists())
            );

    /**
     * Batch evaluation with balanced events.
     */
    private final ScenarioBuilder batchEvaluate = scenario("Batch Evaluate")
            .feed(HeliosTestData.balancedBatchFeeder())
            .exec(
                    http("POST /evaluate/batch")
                            .post("/api/v1/evaluate/batch")
                            .body(StringBody("#{batchBody}"))
                            .check(status().is(200))
                            .check(jsonPath("$.stats.totalEvents").ofInt().gte(1))
                            .check(jsonPath("$.stats.matchRate").ofDouble().saveAs("matchRate"))
            );

    /**
     * Mixed workload — realistic production traffic pattern:
     * 60% single (balanced), 15% trace-BASIC, 5% trace-FULL,
     * 10% batch, 5% match-only, 5% miss-only.
     */
    private final ScenarioBuilder mixedWorkload = scenario("Mixed Workload")
            .randomSwitch().on(
                    new Choice.WithWeight(60, feed(HeliosTestData.balancedEventFeeder()).exec(
                            http("POST /evaluate")
                                    .post("/api/v1/evaluate")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(15, feed(HeliosTestData.balancedEventFeeder()).exec(
                            http("POST /evaluate/trace (BASIC)")
                                    .post("/api/v1/evaluate/trace")
                                    .queryParam("level", "BASIC")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(5, feed(HeliosTestData.balancedEventFeeder()).exec(
                            http("POST /evaluate/trace (FULL)")
                                    .post("/api/v1/evaluate/trace")
                                    .queryParam("level", "FULL")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(10, feed(HeliosTestData.balancedBatchFeeder()).exec(
                            http("POST /evaluate/batch")
                                    .post("/api/v1/evaluate/batch")
                                    .body(StringBody("#{batchBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(5, feed(HeliosTestData.matchingEventFeeder()).exec(
                            http("POST /evaluate (match)")
                                    .post("/api/v1/evaluate")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    )),
                    new Choice.WithWeight(5, feed(HeliosTestData.nonMatchingEventFeeder()).exec(
                            http("POST /evaluate (miss)")
                                    .post("/api/v1/evaluate")
                                    .body(StringBody("#{eventBody}"))
                                    .check(status().is(200))
                    ))
            );

    // ── Load profile ─────────────────────────────────────────────────────────

    {
        setUp(
                // Phase 1: Warm-up with balanced single evaluations
                singleEvaluate.injectOpen(
                        rampUsers(USERS).during(RAMP_SECONDS)
                ),
                // Phase 2: Sustained mixed traffic
                mixedWorkload.injectOpen(
                        nothingFor(RAMP_SECONDS),  // wait for warm-up
                        constantUsersPerSec(USERS).during(STEADY_SECONDS)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile(50.0).lt(50),   // p50 < 50ms
                 global().responseTime().percentile(95.0).lt(200),  // p95 < 200ms
                 global().responseTime().percentile(99.0).lt(500),  // p99 < 500ms
                 global().successfulRequests().percent().gt(99.0)   // >99% success rate
         );
    }
}
