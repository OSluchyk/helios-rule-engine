package com.helios.ruleengine.core.compiler;

/**
 * Exception thrown when rule compilation fails.
 *
 * This is a RuntimeException to avoid forcing checked exception handling
 * throughout the codebase, while still providing clear error messages
 * for compilation failures.
 */
public class CompilationException extends RuntimeException {

    public CompilationException(String message) {
        super(message);
    }

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }

    public CompilationException(Throwable cause) {
        super(cause);
    }
}