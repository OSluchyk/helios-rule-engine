/*
 * Copyright (c) 2025 Helios Rule Engine
 * Licensed under the Apache License, Version 2.0
 */
package com.helios.ruleengine.service.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for loading SQL queries from resource files.
 *
 * <p>This allows SQL queries to be maintained in separate .sql files
 * instead of hardcoded in Java code, making them easier to read, maintain,
 * and optimize.
 *
 * <p><b>Query Format:</b>
 * <pre>
 * -- @name: query_name
 * SELECT * FROM table WHERE id = ?;
 * </pre>
 */
public class SqlLoader {

    private static final Logger logger = Logger.getLogger(SqlLoader.class.getName());

    /**
     * Load all SQL queries from a resource file.
     *
     * @param resourcePath path to SQL file (e.g., "sql/queries.sql")
     * @return map of query names to SQL strings
     */
    public static Map<String, String> loadQueries(String resourcePath) {
        Map<String, String> queries = new HashMap<>();

        try (InputStream is = SqlLoader.class.getClassLoader().getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            String line;
            String currentQueryName = null;
            StringBuilder currentQuery = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Check for query name marker
                if (line.startsWith("-- @name:")) {
                    // Save previous query if exists
                    if (currentQueryName != null && currentQuery.length() > 0) {
                        queries.put(currentQueryName, currentQuery.toString().trim());
                    }

                    // Start new query
                    currentQueryName = line.substring("-- @name:".length()).trim();
                    currentQuery = new StringBuilder();
                }
                // Skip comments and empty lines
                else if (line.startsWith("--") || line.isEmpty()) {
                    continue;
                }
                // Append to current query
                else if (currentQueryName != null) {
                    if (currentQuery.length() > 0) {
                        currentQuery.append(" ");
                    }
                    currentQuery.append(line);
                }
            }

            // Save last query
            if (currentQueryName != null && currentQuery.length() > 0) {
                queries.put(currentQueryName, currentQuery.toString().trim());
            }

            logger.info("Loaded " + queries.size() + " SQL queries from " + resourcePath);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load SQL queries from " + resourcePath, e);
            throw new RuntimeException("Failed to load SQL queries", e);
        }

        return queries;
    }

    /**
     * Load SQL schema from a resource file.
     *
     * @param resourcePath path to SQL file (e.g., "sql/schema.sql")
     * @return SQL schema as a single string
     */
    public static String loadSchema(String resourcePath) {
        try (InputStream is = SqlLoader.class.getClassLoader().getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            StringBuilder schema = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                // Skip comment-only lines
                if (line.trim().startsWith("--") && !line.contains("CREATE") && !line.contains("INDEX")) {
                    continue;
                }
                schema.append(line).append("\n");
            }

            logger.info("Loaded SQL schema from " + resourcePath);
            return schema.toString();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load SQL schema from " + resourcePath, e);
            throw new RuntimeException("Failed to load SQL schema", e);
        }
    }
}
