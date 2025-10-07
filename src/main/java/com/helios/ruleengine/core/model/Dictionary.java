package com.helios.ruleengine.core.model;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A dictionary for encoding and decoding string values to integer IDs.
 * This is a core component for memory optimization and performance improvement.
 */
public class Dictionary implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Object2IntMap<String> stringToId = new Object2IntOpenHashMap<>();
    private final List<String> idToString = new ArrayList<>();

    public Dictionary() {
        stringToId.defaultReturnValue(-1);
    }

    /**
     * Encodes a string value into an integer ID.
     * If the value is not present, it will be added to the dictionary.
     *
     * @param value The string value to encode.
     * @return The integer ID for the value.
     */
    public int encode(String value) {
        // FIX: Explicitly type the lambda parameter 's' as a String to resolve type inference issue.
        return stringToId.computeIfAbsent(value, (String s) -> {
            int id = idToString.size();
            idToString.add(s);
            return id;
        });
    }

    /**
     * Decodes an integer ID back to its string value.
     *
     * @param id The integer ID to decode.
     * @return The original string value.
     */
    public String decode(int id) {
        if (id >= 0 && id < idToString.size()) {
            return idToString.get(id);
        }
        return null;
    }

    /**
     * Gets the integer ID for a given value without adding it if it doesn't exist.
     *
     * @param value The string value.
     * @return The integer ID, or -1 if not found.
     */
    public int getId(String value) {
        return stringToId.getInt(value);
    }

    /**
     * Returns the number of unique entries in the dictionary.
     *
     * @return The size of the dictionary.
     */
    public int size() {
        return idToString.size();
    }
}