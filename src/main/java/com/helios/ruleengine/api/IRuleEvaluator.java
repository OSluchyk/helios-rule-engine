package com.helios.ruleengine.api;


import com.helios.ruleengine.model.Event;
import com.helios.ruleengine.model.MatchResult;

/**
 * Contract for evaluating events against compiled rules.
 */
public interface IRuleEvaluator {

    /**
     * Evaluates an event against rules.
     *
     * @param event the event to evaluate
     * @return match result with matched rules
     */
    MatchResult evaluate(Event event);
}