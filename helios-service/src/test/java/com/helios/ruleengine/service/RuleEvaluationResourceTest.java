package com.helios.ruleengine.service;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Integration tests for the Rule Evaluation REST endpoints.
 */
@QuarkusTest
public class RuleEvaluationResourceTest {

    @Test
    public void testHealthEndpoint() {
        given()
            .when().get("/health/ready")
            .then()
            .statusCode(200);
    }
}
