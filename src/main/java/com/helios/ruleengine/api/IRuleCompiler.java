package com.helios.ruleengine.api;


import com.helios.ruleengine.core.model.EngineModel;

import java.nio.file.Path;

/**
 * Contract for compiling JSON rules into an executable engine model.
 */
public interface IRuleCompiler {

    /**
     * Compiles rules from a JSON file.
     *
     * @param rulesPath path to JSON rules file
     * @return compiled engine model
     * @throws Exception if compilation fails
     */
    EngineModel compile(Path rulesPath) throws Exception;
}