package com.helios.ruleengine.core;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import com.helios.ruleengine.model.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

public class MemoryLayoutTest {

    @Test
    void verifyCompactHeaders() {
        Predicate predicate = new Predicate(1, Predicate.Operator.EQUAL_TO, 1);
        String layout = ClassLayout.parseInstance(predicate).toPrintable();

        System.out.println("====== JOL Analysis: Predicate Object Layout ======");
        System.out.println(layout);
        System.out.println("==================================================");

        // Correctly parse the header size from the layout string
        long headerSize = -1;
        for(String line : layout.split("\n")) {
            if (line.contains("(object header: class)")) {
                headerSize = Long.parseLong(line.trim().split("\\s+")[0]) + 4;
                break;
            }
        }

        assertThat(headerSize).as("Object header size should be 12 bytes with compact headers").isEqualTo(12);
        System.out.printf("SUCCESS: Verified object header size is %d bytes, confirming compact headers are in use.%n", headerSize);
    }
}