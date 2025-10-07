package com.helios.ruleengine.core.bitmap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntList;
import org.roaringbitmap.RoaringBitmap;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Adaptive bitmap representation that automatically selects the most
 * memory-efficient format based on density and cardinality.
 *
 * Representations:
 * - Ultra-sparse (<32 elements): Sorted int array
 * - Sparse (32-1000 elements, <10% density): RoaringBitmap
 * - Dense (>50% density): BitSet
 * - Moderate: RoaringBitmap with RLE
 */
public class AdaptiveBitmapManager {
    private static final Logger logger = Logger.getLogger(AdaptiveBitmapManager.class.getName());

    // Thresholds for representation selection
    private static final int ULTRA_SPARSE_THRESHOLD = 32;
    private static final int SPARSE_THRESHOLD = 1000;
    private static final double DENSE_THRESHOLD = 0.5;
    private static final double SPARSE_DENSITY = 0.1;

    // Memory tracking
    private static final AtomicLong totalMemoryUsed = new AtomicLong();
    private static final AtomicLong totalBitmaps = new AtomicLong();

    public enum BitmapType {
        ULTRA_SPARSE_ARRAY,
        SPARSE_ROARING,
        DENSE_BITSET,
        MODERATE_ROARING_RLE
    }

    public RoaringBitmap getOptimalBitmap(int totalCombinations) {
        return new RoaringBitmap();
    }

    /**
     * Adaptive bitmap that morphs between representations.
     */
    public static class AdaptiveBitmap {
        private BitmapType type;
        private Object data;
        private int maxValue;
        private int cardinality;

        // Statistics for adaptive tuning
        private int readCount = 0;
        private int writeCount = 0;
        private long lastMorphTime = System.nanoTime();

        public AdaptiveBitmap() {
            this(BitmapType.ULTRA_SPARSE_ARRAY);
        }

        public AdaptiveBitmap(BitmapType initialType) {
            this.type = initialType;
            initializeData();
        }

        private void initializeData() {
            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    data = new IntArrayList();
                    break;
                case SPARSE_ROARING:
                    data = new RoaringBitmap();
                    break;
                case DENSE_BITSET:
                    data = new BitSet();
                    break;
                case MODERATE_ROARING_RLE:
                    RoaringBitmap rb = new RoaringBitmap();
                    rb.runOptimize(); // Enable RLE
                    data = rb;
                    break;
            }
        }

        /**
         * Add a value and potentially morph to a better representation.
         */
        public void add(int value) {
            writeCount++;
            maxValue = Math.max(maxValue, value);

            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    IntList array = (IntList) data;
                    if (!array.contains(value)) {
                        array.add(value);
                        ((IntArrayList) array).sort(null);
                        cardinality++;

                        // Check if we should morph
                        if (cardinality > ULTRA_SPARSE_THRESHOLD) {
                            morphToRoaring();
                        }
                    }
                    break;

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    RoaringBitmap rb = (RoaringBitmap) data;
                    rb.add(value);
                    cardinality = rb.getCardinality();

                    // Check density for potential morph to BitSet
                    if (shouldMorphToDense()) {
                        morphToBitSet();
                    }
                    break;

                case DENSE_BITSET:
                    BitSet bs = (BitSet) data;
                    if (!bs.get(value)) {
                        bs.set(value);
                        cardinality++;
                    }
                    break;
            }
        }

        /**
         * Check if a value is present.
         */
        public boolean contains(int value) {
            readCount++;

            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    return ((IntList) data).contains(value);

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    return ((RoaringBitmap) data).contains(value);

                case DENSE_BITSET:
                    return ((BitSet) data).get(value);

                default:
                    return false;
            }
        }

        /**
         * Perform bitwise AND operation with another bitmap.
         */
        public AdaptiveBitmap and(AdaptiveBitmap other) {
            // Convert both to RoaringBitmap for operation
            RoaringBitmap rb1 = toRoaringBitmap();
            RoaringBitmap rb2 = other.toRoaringBitmap();

            RoaringBitmap result = RoaringBitmap.and(rb1, rb2);

            // Create result with appropriate type
            return fromRoaringBitmap(result);
        }

        /**
         * Iterate over set bits efficiently.
         */
        public void forEach(IntConsumer consumer) {
            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    ((IntList) data).forEach(consumer);
                    break;

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    ((RoaringBitmap) data).forEach((org.roaringbitmap.IntConsumer) consumer::accept);
                    break;

                case DENSE_BITSET:
                    BitSet bs = (BitSet) data;
                    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                        consumer.accept(i);
                    }
                    break;
            }
        }

        private void morphToRoaring() {
            logger.fine(String.format(
                    "Morphing from ULTRA_SPARSE to ROARING: cardinality=%d", cardinality
            ));

            IntList oldArray = (IntList) data;
            RoaringBitmap rb = new RoaringBitmap();
            oldArray.forEach((IntConsumer) rb::add);

            type = BitmapType.SPARSE_ROARING;
            data = rb;
            lastMorphTime = System.nanoTime();
        }

        private void morphToBitSet() {
            logger.fine(String.format(
                    "Morphing from ROARING to BITSET: density=%.2f", getDensity()
            ));

            RoaringBitmap rb = (RoaringBitmap) data;
            BitSet bs = new BitSet(maxValue + 1);
            rb.forEach((IntConsumer) bs::set);

            type = BitmapType.DENSE_BITSET;
            data = bs;
            lastMorphTime = System.nanoTime();
        }

        private boolean shouldMorphToDense() {
            // Don't morph too frequently
            if (System.nanoTime() - lastMorphTime < 1_000_000_000L) {
                return false;
            }

            return getDensity() > DENSE_THRESHOLD && maxValue < 1_000_000;
        }

        private double getDensity() {
            if (maxValue == 0) return 0;
            return (double) cardinality / (maxValue + 1);
        }

        private RoaringBitmap toRoaringBitmap() {
            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    RoaringBitmap rb = new RoaringBitmap();
                    ((IntList) data).forEach((IntConsumer) rb::add);
                    return rb;

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    return (RoaringBitmap) data;

                case DENSE_BITSET:
                    BitSet bs = (BitSet) data;
                    RoaringBitmap result = new RoaringBitmap();
                    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                        result.add(i);
                    }
                    return result;

                default:
                    return new RoaringBitmap();
            }
        }

        private static AdaptiveBitmap fromRoaringBitmap(RoaringBitmap rb) {
            int card = rb.getCardinality();

            if (card <= ULTRA_SPARSE_THRESHOLD) {
                AdaptiveBitmap result = new AdaptiveBitmap(BitmapType.ULTRA_SPARSE_ARRAY);
                rb.forEach((org.roaringbitmap.IntConsumer) result::add);
                return result;
            } else if (card <= SPARSE_THRESHOLD) {
                AdaptiveBitmap result = new AdaptiveBitmap(BitmapType.SPARSE_ROARING);
                result.data = rb;
                result.cardinality = card;
                return result;
            } else {
                // Check if RLE would be beneficial
                rb.runOptimize();
                AdaptiveBitmap result = new AdaptiveBitmap(BitmapType.MODERATE_ROARING_RLE);
                result.data = rb;
                result.cardinality = card;
                return result;
            }
        }

        /**
         * Get estimated memory usage in bytes.
         */
        public long getMemoryUsage() {
            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    return 24 + (cardinality * 4); // Object overhead + int array

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    return ((RoaringBitmap) data).serializedSizeInBytes();

                case DENSE_BITSET:
                    return 24 + ((maxValue / 8) + 1); // Object overhead + bit array

                default:
                    return 0;
            }
        }

        /**
         * Serialize to bytes for storage/network.
         */
        public byte[] serialize() {
            ByteBuffer buffer = ByteBuffer.allocate(getSerializedSize());

            buffer.put((byte) type.ordinal());
            buffer.putInt(cardinality);
            buffer.putInt(maxValue);

            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    IntList array = (IntList) data;
                    buffer.putInt(array.size());
                    array.forEach((IntConsumer) buffer::putInt);
                    break;

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    RoaringBitmap rb = (RoaringBitmap) data;
                    rb.serialize(buffer);
                    break;

                case DENSE_BITSET:
                    BitSet bs = (BitSet) data;
                    byte[] bytes = bs.toByteArray();
                    buffer.putInt(bytes.length);
                    buffer.put(bytes);
                    break;
            }

            return buffer.array();
        }

        private int getSerializedSize() {
            int baseSize = 1 + 4 + 4; // type + cardinality + maxValue

            switch (type) {
                case ULTRA_SPARSE_ARRAY:
                    return baseSize + 4 + (cardinality * 4);

                case SPARSE_ROARING:
                case MODERATE_ROARING_RLE:
                    return baseSize + ((RoaringBitmap) data).serializedSizeInBytes();

                case DENSE_BITSET:
                    return baseSize + 4 + ((BitSet) data).toByteArray().length;

                default:
                    return baseSize;
            }
        }
    }

    /**
     * Factory method to create optimal bitmap based on expected characteristics.
     */
    public static AdaptiveBitmap createOptimal(int expectedCardinality, int maxValue) {
        double expectedDensity = maxValue > 0 ? (double) expectedCardinality / maxValue : 0;

        if (expectedCardinality <= ULTRA_SPARSE_THRESHOLD) {
            return new AdaptiveBitmap(BitmapType.ULTRA_SPARSE_ARRAY);
        } else if (expectedDensity > DENSE_THRESHOLD) {
            return new AdaptiveBitmap(BitmapType.DENSE_BITSET);
        } else if (expectedCardinality <= SPARSE_THRESHOLD) {
            return new AdaptiveBitmap(BitmapType.SPARSE_ROARING);
        } else {
            return new AdaptiveBitmap(BitmapType.MODERATE_ROARING_RLE);
        }
    }

    /**
     * Get global memory statistics.
     */
    public static String getMemoryStats() {
        return String.format(
                "AdaptiveBitmaps: count=%d, totalMemory=%.2f MB, avgSize=%.2f KB",
                totalBitmaps.get(),
                totalMemoryUsed.get() / (1024.0 * 1024.0),
                totalBitmaps.get() > 0 ?
                        (totalMemoryUsed.get() / totalBitmaps.get()) / 1024.0 : 0
        );
    }
}