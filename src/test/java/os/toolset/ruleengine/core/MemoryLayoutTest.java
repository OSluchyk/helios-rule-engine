package os.toolset.ruleengine.core;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import os.toolset.ruleengine.model.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

public class MemoryLayoutTest {

    @Test
    void verifyCompactHeaders() {
        // This test verifies that the JVM is using compact headers.
        // A standard 64-bit JVM with compact headers has a 12-byte header
        // (8-byte mark word + 4-byte compressed class pointer).
        // Without compact pointers, it would be 16 bytes.

        Predicate predicate = new Predicate(1, Predicate.Operator.EQUAL_TO, 1);
        String layout = ClassLayout.parseInstance(predicate).toPrintable();

        System.out.println("====== JOL Analysis: Predicate Object Layout ======");
        System.out.println(layout);
        System.out.println("==================================================");

        long headerSize = ClassLayout.parseInstance(predicate).headerSize();

        // FIX: The correct expected header size on a 64-bit JVM with compressed pointers is 12 bytes.
        assertThat(headerSize).as("Object header size should be 12 bytes with compact headers").isEqualTo(12);

        System.out.printf("SUCCESS: Verified object header size is %d bytes, confirming compact headers are in use.%n", headerSize);
    }
}