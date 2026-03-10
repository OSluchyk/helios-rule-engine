package com.helios.ruleengine.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Spike / stress test simulation for the Helios Rule Engine.
 *
 * <p>Seeds the same 20-rule ruleset as the main simulation, then hammers
 * the engine with sudden bursts of balanced (50/50 match) traffic.
 * Pattern: baseline → spike → recovery → double-spike → cool-down.
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn gatling:test -Pspike
 *   # or
 *   mvn gatling:test -Dgatling.simulationClass=com.helios.ruleengine.gatling.SpikeTestSimulation
 * </pre>
 */
public class SpikeTestSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final int SPIKE_USERS = Integer.getInteger("gatling.spikeUsers", 200);
    private static final int BASELINE_USERS = Integer.getInteger("gatling.baselineUsers", 10);

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

    private final ScenarioBuilder spikeEvaluate = scenario("Spike — Single Evaluate")
            .feed(HeliosTestData.balancedEventFeeder())
            .exec(
                    http("POST /evaluate")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{eventBody}"))
                            .check(status().in(200, 503)) // allow backpressure under spike
            );

    private final ScenarioBuilder spikeBatch = scenario("Spike — Batch Evaluate")
            .feed(HeliosTestData.balancedBatchFeeder())
            .exec(
                    http("POST /evaluate/batch")
                            .post("/api/v1/evaluate/batch")
                            .body(StringBody("#{batchBody}"))
                            .check(status().in(200, 503))
            );

    // ── Load profile ─────────────────────────────────────────────────────────

    {
        setUp(
                // Single-event spike pattern
                spikeEvaluate.injectOpen(
                        // Phase 1: baseline
                        constantUsersPerSec(BASELINE_USERS).during(15),
                        // Phase 2: spike
                        rampUsers(SPIKE_USERS).during(5),
                        // Phase 3: recovery
                        constantUsersPerSec(BASELINE_USERS).during(15),
                        // Phase 4: double spike
                        rampUsers(SPIKE_USERS * 2).during(5),
                        // Phase 5: cool-down
                        constantUsersPerSec(BASELINE_USERS).during(20)
                ),
                // Batch traffic running alongside
                spikeBatch.injectOpen(
                        constantUsersPerSec(2).during(15),
                        rampUsers(BASELINE_USERS).during(5),
                        constantUsersPerSec(2).during(15),
                        rampUsers(BASELINE_USERS * 2).during(5),
                        constantUsersPerSec(2).during(20)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().successfulRequests().percent().gt(95.0),  // allow some failures under spike
                 global().responseTime().percentile(99.0).lt(2000) // p99 < 2s even under spike
         );
    }
}
