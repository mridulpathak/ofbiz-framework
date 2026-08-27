/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.entity.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

public class SchemaFingerprintTests {

    @Test
    void resolveComponentTableNamesFindsExamplesRealTables() throws Exception {
        // plugins/example declares its own entity model (see MigrationSupportTests), so this
        // exercises the real ModelReader/ModelEntity table-name resolution against real config.
        Set<String> tableNames = SchemaFingerprint.resolveComponentTableNames("default", "example");

        assertTrue(tableNames.contains("EXAMPLE"), "expected the Example entity's table, got: " + tableNames);
    }

    @Test
    void computeIsDeterministicForTheSameSchema() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fingerprintdeterministic;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20), WIDGET_NAME VARCHAR(100))");

            String first = SchemaFingerprint.compute(conn, new TreeSet<>(Set.of("WIDGET")));
            String second = SchemaFingerprint.compute(conn, new TreeSet<>(Set.of("WIDGET")));

            assertEquals(first, second, "the same schema must always produce the same fingerprint");
        }
    }

    @Test
    void computeChangesWhenAColumnIsAdded() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fingerprintchanges;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20))");
            String before = SchemaFingerprint.compute(conn, new TreeSet<>(Set.of("WIDGET")));

            stmt.execute("ALTER TABLE WIDGET ADD COLUMN WIDGET_NAME VARCHAR(100)");
            String after = SchemaFingerprint.compute(conn, new TreeSet<>(Set.of("WIDGET")));

            assertNotEquals(before, after, "adding a column must change the fingerprint");
        }
    }

    @Test
    void loadReturnsNullWhenNothingHasBeenStoredYet() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fingerprintloadempty;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            String fingerprint = SchemaFingerprint.load(conn, "nonexistent-component");

            assertNull(fingerprint);
        }
    }

    @Test
    void storeThenLoadRoundTripsTheFingerprint() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fingerprintroundtrip;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            SchemaFingerprint.store(conn, "widgettest", "abc123");

            assertEquals("abc123", SchemaFingerprint.load(conn, "widgettest"));
        }
    }

    @Test
    void storeOverwritesAPreviouslyStoredFingerprintForTheSameComponent() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fingerprintoverwrite;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            SchemaFingerprint.store(conn, "widgettest2", "first");
            SchemaFingerprint.store(conn, "widgettest2", "second");

            assertEquals("second", SchemaFingerprint.load(conn, "widgettest2"));
        }
    }
}
