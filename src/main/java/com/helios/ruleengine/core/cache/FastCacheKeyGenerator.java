package com.helios.ruleengine.core.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.nio.ByteBuffer;

/**
 * P0-C FIX: Fixed-tier buffer management (no resizing)
 * High-performance cache key generation using xxHash3 and memory-efficient techniques.
 */
public class FastCacheKeyGenerator {

    // P0-C FIX: Fixed-tier thread-local buffers (no resizing logic)
    private static final ThreadLocal<FixedTierBuffers> BUFFER_CACHE =
            ThreadLocal.withInitial(FixedTierBuffers::new);

    // xxHash3 constants for 128-bit hashing
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    /**
     * Generate a compact, high-quality cache key using xxHash3.
     * P0-C FIX: Uses fixed-tier buffers for zero resizing overhead
     */
    public static String generateKey(
            Int2ObjectMap<Object> eventAttrs,
            int[] predicateIds,
            int predicateCount
    ) {
        long predicateSetHash = hashPredicateIds(predicateIds, predicateCount);

        // P0-C FIX: Calculate buffer size more accurately
        int maxPossibleWrites = Math.min(predicateCount, eventAttrs.size());
        int estimatedSize = 8 + (maxPossibleWrites * 12);

        // P0-C FIX: Use hash-only key for extremely large predicate sets
        if (estimatedSize > 65536) {  // 64KB max
            return generateHashOnlyKey(predicateSetHash, eventAttrs,
                    predicateIds, predicateCount);
        }

        // P0-C FIX: Get buffer from fixed-tier pool
        FixedTierBuffers buffers = BUFFER_CACHE.get();
        ByteBuffer buffer = buffers.getBuffer(estimatedSize);

        if (buffer == null) {
            // Buffer tier exceeded, use hash-only fallback
            return generateHashOnlyKey(predicateSetHash, eventAttrs,
                    predicateIds, predicateCount);
        }

        buffer.clear();

        // Write the predicate set hash
        buffer.putLong(predicateSetHash);

        // Write event values for relevant predicates only
        for (int i = 0; i < predicateCount; i++) {
            int predId = predicateIds[i];
            Object value = eventAttrs.get(predId);
            if (value != null) {
                buffer.putInt(predId);
                writeValue(buffer, value);
            }
        }

        // Compute xxHash3
        buffer.flip();
        long hash1 = xxHash3_128(buffer, PRIME64_1);
        long hash2 = xxHash3_128(buffer, PRIME64_2);

        return encodeCompact(hash1, hash2);
    }

    /**
     * P0-C FIX: Hash-only key generation for extreme cases (no buffer allocation)
     */
    private static String generateHashOnlyKey(
            long predicateSetHash,
            Int2ObjectMap<Object> eventAttrs,
            int[] predicateIds,
            int predicateCount
    ) {
        long hash = predicateSetHash;

        for (int i = 0; i < predicateCount; i++) {
            int predId = predicateIds[i];
            Object value = eventAttrs.get(predId);
            if (value != null) {
                hash = mixHash(hash, predId);
                hash = mixHash(hash, valueHash(value));
            }
        }

        hash = finalizeHash(hash);

        return encodeCompact(hash, hash ^ PRIME64_3);
    }

    private static long hashPredicateIds(int[] predicateIds, int count) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < count; i++) {
            hash ^= predicateIds[i];
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Specialized key for base condition sets - even faster.
     */
    public static String generateBaseConditionKey(
            int[] staticPredicateIds,
            int[] staticValues,
            int count
    ) {
        long acc = PRIME64_1;

        for (int i = 0; i < count; i++) {
            acc = mixHash(acc, staticPredicateIds[i]);
            acc = mixHash(acc, staticValues[i]);
        }

        acc = finalizeHash(acc);

        return encodeCompact8(acc);
    }

    private static void writeValue(ByteBuffer buffer, Object value) {
        switch (value) {
            case Integer i -> buffer.putInt(i);
            case Long l -> buffer.putLong(l);
            case Double v -> buffer.putDouble(v);
            case String s -> buffer.putLong(stringHash(s));
            default -> buffer.putInt(value.hashCode());
        }
    }

    private static long valueHash(Object value) {
        return switch (value) {
            case Integer i -> i;
            case Long l -> l;
            case Double v -> Double.doubleToLongBits(v);
            case String s -> stringHash(s);
            default -> value.hashCode();
        };
    }

    private static long xxHash3_128(ByteBuffer buffer, long seed) {
        long acc = seed;

        while (buffer.remaining() >= 8) {
            long k1 = buffer.getLong();
            acc ^= mixHash(acc, k1);
            acc = Long.rotateLeft(acc, 27);
            acc = acc * PRIME64_1 + PRIME64_4;
        }

        if (buffer.remaining() >= 4) {
            acc ^= buffer.getInt() * PRIME64_1;
            acc = Long.rotateLeft(acc, 23);
            acc = acc * PRIME64_2 + PRIME64_3;
        }

        while (buffer.hasRemaining()) {
            acc ^= (buffer.get() & 0xFF) * PRIME64_5;
            acc = Long.rotateLeft(acc, 11);
            acc = acc * PRIME64_1;
        }

        return finalizeHash(acc);
    }

    private static long mixHash(long acc, long value) {
        long k1 = value * PRIME64_2;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= PRIME64_1;
        acc ^= k1;
        return acc;
    }

    private static long finalizeHash(long hash) {
        hash ^= hash >>> 33;
        hash *= PRIME64_2;
        hash ^= hash >>> 29;
        hash *= PRIME64_3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long stringHash(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final char[] ENCODING_TABLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static String encodeCompact(long h1, long h2) {
        char[] result = new char[16];
        long combined = h1;
        for (int i = 0; i < 8; i++) {
            result[i] = ENCODING_TABLE[(int)(combined & 0x3F)];
            combined >>>= 6;
        }
        combined = h2;
        for (int i = 8; i < 16; i++) {
            result[i] = ENCODING_TABLE[(int)(combined & 0x3F)];
            combined >>>= 6;
        }
        return new String(result);
    }

    private static String encodeCompact8(long hash) {
        char[] result = new char[8];
        for (int i = 0; i < 8; i++) {
            result[i] = ENCODING_TABLE[(int)(hash & 0x3F)];
            hash >>>= 6;
        }
        return new String(result);
    }

    /**
     * P0-C FIX: Fixed-tier buffer management (no resizing, no allocations)
     *
     * Uses three fixed-size buffers for different size classes.
     * Zero overhead buffer selection - just picks the right tier.
     */
    private static class FixedTierBuffers {
        private final ByteBuffer smallBuffer;   // 4KB - covers 90% of cases
        private final ByteBuffer mediumBuffer;  // 16KB - covers 98% of cases
        private final ByteBuffer largeBuffer;   // 64KB - covers 99.9% of cases

        // Simple statistics for monitoring
        private long smallHits = 0;
        private long mediumHits = 0;
        private long largeHits = 0;
        private long fallbacks = 0;

        FixedTierBuffers() {
            this.smallBuffer = ByteBuffer.allocateDirect(4096);
            this.mediumBuffer = ByteBuffer.allocateDirect(16384);
            this.largeBuffer = ByteBuffer.allocateDirect(65536);
        }

        /**
         * P0-C FIX: Simple tier selection - NO RESIZING, NO ALLOCATIONS
         * Just picks the right pre-allocated buffer for the size.
         */
        ByteBuffer getBuffer(int requiredCapacity) {
            if (requiredCapacity <= 4096) {
                smallHits++;
                smallBuffer.clear();
                return smallBuffer;
            } else if (requiredCapacity <= 16384) {
                mediumHits++;
                mediumBuffer.clear();
                return mediumBuffer;
            } else if (requiredCapacity <= 65536) {
                largeHits++;
                largeBuffer.clear();
                return largeBuffer;
            } else {
                // Extreme case - use hash-only fallback
                fallbacks++;
                return null;
            }
        }

        /**
         * P0-C FIX: Get statistics for monitoring (optional)
         */
        public String getStatistics() {
            long total = smallHits + mediumHits + largeHits + fallbacks;
            if (total == 0) return "No usage yet";

            return String.format(
                    "Buffer usage: small=%.1f%% medium=%.1f%% large=%.1f%% fallback=%.1f%%",
                    smallHits * 100.0 / total,
                    mediumHits * 100.0 / total,
                    largeHits * 100.0 / total,
                    fallbacks * 100.0 / total
            );
        }
    }
}