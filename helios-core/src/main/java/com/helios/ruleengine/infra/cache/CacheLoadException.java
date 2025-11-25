package com.helios.ruleengine.infra.cache;

class CacheLoadException extends RuntimeException {
    CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
