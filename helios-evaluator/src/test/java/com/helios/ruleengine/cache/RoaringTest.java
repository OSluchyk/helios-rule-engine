package com.helios.ruleengine.cache;

import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

public class RoaringTest {
    public void test() {
        RoaringBitmap rb = new RoaringBitmap();
        // Check if this method exists
        MutableRoaringBitmap mrb = rb.toMutableRoaringBitmap();
    }
}
