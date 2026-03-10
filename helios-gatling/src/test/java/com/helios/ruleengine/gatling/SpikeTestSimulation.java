package com.helios.ruleengine.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Spike / stress test simulation for the Helios Rule Engine.
 *
 * <p>Tests engine resilience under sudden bursts of traffic.
 * Pattern: low baseline → sudden spike → back to baseline → repeat.
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

    private static final String[] COUNTRIES = {"US", "GB", "DE", "FR", "JP", "XX"};

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    private static Iterator<Map<String, Object>> feeder() {
        return Stream.generate(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            String body = String.format("""
                    {
                      "eventId": "spike-%s",
                      "eventType": "ORDER",
                      "attributes": {
                        "AMOUNT": %d,
                        "COUNTRY": "%s",
                        "USER_RISK_SCORE": %d,
                        "type": "order"
                      }
                    }""",
                    UUID.randomUUID(),
                    rng.nextInt(1, 100_001),
                    COUNTRIES[rng.nextInt(COUNTRIES.length)],
                    rng.nextInt(0, 101));
            return Map.<String, Object>of("body", body);
        }).iterator();
    }

    private final ScenarioBuilder spikeScenario = scenario("Spike Test")
            .feed(feeder())
            .exec(
                    http("POST /evaluate")
                            .post("/api/v1/evaluate")
                            .body(StringBody("#{body}"))
                            .check(status().in(200, 503)) // allow backpressure
            );

    {
        setUp(
                spikeScenario.injectOpen(
                        // Phase 1: baseline
                        constantUsersPerSec(BASELINE_USERS).during(15),
                        // Phase 2: spike
                        rampUsers(SPIKE_USERS).during(5),
                        // Phase 3: recovery
                        constantUsersPerSec(BASELINE_USERS).during(15),
                        // Phase 4: second spike (higher)
                        rampUsers(SPIKE_USERS * 2).during(5),
                        // Phase 5: cool-down
                        constantUsersPerSec(BASELINE_USERS).during(20)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().successfulRequests().percent().gt(95.0),  // allow some failures under spike
                 global().responseTime().percentile(99.0).lt(2000) // p99 < 2s even under spike
         );
    }
}
