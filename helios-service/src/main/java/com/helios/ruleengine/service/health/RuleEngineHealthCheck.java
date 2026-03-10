package com.helios.ruleengine.service.health;

import com.helios.ruleengine.infra.management.EngineModelManager;
import com.helios.ruleengine.runtime.model.EngineModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Health check for the rule engine.
 * Verifies that the EngineModel is loaded and contains rules.
 */
@Readiness
@ApplicationScoped
public class RuleEngineHealthCheck implements HealthCheck {

    @Inject
    EngineModelManager modelManager;

    @Override
    public HealthCheckResponse call() {
        EngineModel model = modelManager.getEngineModel();

        if (model != null) {
            return HealthCheckResponse.builder()
                    .name("rule-engine")
                    .up()
                    .withData("numRules", (long) model.getNumRules())
                    .withData("status", model.getNumRules() > 0 ? "active" : "empty_awaiting_rules")
                    .build();
        } else {
            return HealthCheckResponse.builder()
                    .name("rule-engine")
                    .down()
                    .withData("reason", "EngineModel not initialized")
                    .build();
        }
    }
}
