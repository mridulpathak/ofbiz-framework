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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class SchemaDriftAuditorTests {

    @Test
    void noDriftWhenSchemasMatch() throws Exception {
        try (Connection live = DriverManager.getConnection("jdbc:h2:mem:driftlive1;DB_CLOSE_DELAY=-1", "sa", "");
                Connection reference = DriverManager.getConnection("jdbc:h2:mem:driftref1;DB_CLOSE_DELAY=-1", "sa", "")) {
            createWidgetTable(live);
            createWidgetTable(reference);

            SchemaDriftAuditor auditor = new SchemaDriftAuditor(live, reference);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertTrue(findings.isEmpty(), "identical schemas should report no drift");
        }
    }

    @Test
    void reportsMissingColumnAsDrift() throws Exception {
        try (Connection live = DriverManager.getConnection("jdbc:h2:mem:driftlive2;DB_CLOSE_DELAY=-1", "sa", "");
                Connection reference = DriverManager.getConnection("jdbc:h2:mem:driftref2;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = live.createStatement()) {
                stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY)");
            }
            createWidgetTable(reference);

            SchemaDriftAuditor auditor = new SchemaDriftAuditor(live, reference);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertEquals(1, findings.size());
            assertEquals("WIDGET", findings.get(0).tableName());
            assertTrue(findings.get(0).description().contains("WIDGET_NAME"),
                    "finding should mention the missing column: " + findings.get(0).description());
        }
    }

    @Test
    void reportsExtraColumnAsDrift() throws Exception {
        try (Connection live = DriverManager.getConnection("jdbc:h2:mem:driftlive3;DB_CLOSE_DELAY=-1", "sa", "");
                Connection reference = DriverManager.getConnection("jdbc:h2:mem:driftref3;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = live.createStatement()) {
                stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100), WIDGET_EXTRA VARCHAR(100))");
            }
            createWidgetTable(reference);

            SchemaDriftAuditor auditor = new SchemaDriftAuditor(live, reference);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertEquals(1, findings.size());
            assertEquals("WIDGET", findings.get(0).tableName());
            assertTrue(findings.get(0).description().contains("WIDGET_EXTRA"),
                    "finding should mention the extra column: " + findings.get(0).description());
        }
    }

    @Test
    void columnsBySchemaOnlySeesTheConfiguredSchemaWhenAnotherSchemaHasATableWithTheSameName(@TempDir Path tempDir) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "schemaqualified;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA TENANT1");
            stmt.execute("CREATE TABLE PUBLIC.EXAMPLE (EXAMPLE_ID VARCHAR(20) NOT NULL PRIMARY KEY)");
            stmt.execute("CREATE TABLE TENANT1.EXAMPLE (EXAMPLE_ID VARCHAR(20) NOT NULL PRIMARY KEY, TENANT_FIELD VARCHAR(20))");

            Map<String, Set<SchemaDriftAuditor.ColumnSignature>> publicColumnsBySchema =
                    SchemaDriftAuditor.columnsBySchema(conn, "PUBLIC");
            Map<String, Set<SchemaDriftAuditor.ColumnSignature>> tenantColumnsBySchema =
                    SchemaDriftAuditor.columnsBySchema(conn, "TENANT1");
            String publicKey = SchemaDriftAuditor.normalizedTableName(conn, "EXAMPLE");
            String tenantKey = SchemaDriftAuditor.normalizedTableName(conn, "EXAMPLE");

            Set<SchemaDriftAuditor.ColumnSignature> publicColumns = publicColumnsBySchema.get(publicKey);
            Set<SchemaDriftAuditor.ColumnSignature> tenantColumns = tenantColumnsBySchema.get(tenantKey);

            assertFalse(hasColumnNamed(publicColumns, "TENANT_FIELD"),
                    "PUBLIC.EXAMPLE must not pick up TENANT1.EXAMPLE's column, got: " + publicColumns);
            assertTrue(hasColumnNamed(tenantColumns, "TENANT_FIELD"),
                    "TENANT1.EXAMPLE should see its own column, got: " + tenantColumns);
        }
    }

    @Test
    void reportsColumnTypeChangeAsDrift() throws Exception {
        try (Connection live = DriverManager.getConnection("jdbc:h2:mem:driftlive4;DB_CLOSE_DELAY=-1", "sa", "");
                Connection reference = DriverManager.getConnection("jdbc:h2:mem:driftref4;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = live.createStatement()) {
                stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(50))");
            }
            try (Statement stmt = reference.createStatement()) {
                stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100))");
            }

            SchemaDriftAuditor auditor = new SchemaDriftAuditor(live, reference);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertEquals(1, findings.size(),
                    "a column that merely changed size must be reported once as a 'changed' finding, not as a "
                            + "separate missing-plus-extra pair: " + findings);
            assertEquals("WIDGET", findings.get(0).tableName());
            String description = findings.get(0).description();
            assertTrue(description.contains("WIDGET_NAME"),
                    "finding should mention the changed column: " + description);
            assertTrue(description.contains("50"), "finding should mention the old (live) column size: " + description);
            assertTrue(description.contains("100"), "finding should mention the new (reference) column size: " + description);
        }
    }

    @Test
    void findDriftDoesNotSilentlyDropAGenuineDifferenceWhenLiveHasDuplicateColumnNames(@TempDir Path tempDir)
            throws Exception {
        // Recreates the real-world scenario this guards against: two schemas (standing in for two
        // MySQL databases on the same server) both have a WIDGET table with a WIDGET_NAME column of
        // different sizes. An UNSCOPED getColumns call (as columnsBySchema issues when passed a null
        // schema name) merges both schemas' columns under the bare "WIDGET" table name, so the live
        // side's Set<ColumnSignature> legitimately contains two same-named-but-different WIDGET_NAME
        // entries - exactly what hasDuplicateColumnNames flags. Before the fix, the name-keyed
        // Map.put() in findDrift's comparison would silently keep only one of the two entries; if
        // that happened to be the one matching reference, the genuine VARCHAR(50) difference would
        // vanish and the table would be falsely reported as having zero drift.
        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "ambiguousdup;DB_CLOSE_DELAY=-1";
        try (Connection live = DriverManager.getConnection(jdbcUrl, "sa", "");
                Connection reference = DriverManager.getConnection("jdbc:h2:mem:driftref5;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = live.createStatement()) {
                stmt.execute("CREATE SCHEMA TENANT1");
                stmt.execute("CREATE TABLE PUBLIC.WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, "
                        + "WIDGET_NAME VARCHAR(50))");
                stmt.execute("CREATE TABLE TENANT1.WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, "
                        + "WIDGET_NAME VARCHAR(100))");
            }
            try (Statement stmt = reference.createStatement()) {
                stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100))");
            }

            // Sanity-check the setup actually produces the ambiguity this test exists to exercise,
            // before relying on findDrift's own (unscoped, i.e. null/null) comparison to handle it.
            Map<String, Set<SchemaDriftAuditor.ColumnSignature>> unscopedLiveColumns =
                    SchemaDriftAuditor.columnsBySchema(live, null);
            String liveKey = SchemaDriftAuditor.normalizedTableName(live, "WIDGET");
            assertTrue(SchemaDriftAuditor.hasDuplicateColumnNames(unscopedLiveColumns.get(liveKey)),
                    "test setup must merge both schemas' WIDGET_NAME columns into one ambiguous set: "
                            + unscopedLiveColumns.get(liveKey));

            SchemaDriftAuditor auditor = new SchemaDriftAuditor(live, reference);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertFalse(findings.isEmpty(),
                    "a genuine difference (the VARCHAR(50) WIDGET_NAME not present in reference) must never be "
                            + "silently swallowed just because the live side has an ambiguous, duplicate-named "
                            + "column: " + findings);
        }
    }

    @Test
    void hasDuplicateColumnNamesIsFalseWhenEveryColumnNameIsUnique() {
        Set<SchemaDriftAuditor.ColumnSignature> columns = Set.of(
                SchemaDriftAuditor.ColumnSignature.withoutCharset("WIDGET_ID", "VARCHAR", 20, 0, false),
                SchemaDriftAuditor.ColumnSignature.withoutCharset("WIDGET_NAME", "VARCHAR", 100, 0, true));

        assertFalse(SchemaDriftAuditor.hasDuplicateColumnNames(columns),
                "no column name repeats, so this must not be flagged ambiguous");
    }

    @Test
    void hasDuplicateColumnNamesIsTrueWhenTheSameColumnNameAppearsTwice() {
        // Simulates what columnsBySchema's unscoped getColumns call can produce on MySQL when two
        // databases on the same server both have a table with this name: two ColumnSignatures that
        // share a columnName() but differ in every other respect, because they really describe two
        // different databases' columns merged under one bare table name.
        Set<SchemaDriftAuditor.ColumnSignature> columns = Set.of(
                SchemaDriftAuditor.ColumnSignature.withoutCharset("WIDGET_NAME", "VARCHAR", 50, 0, true),
                SchemaDriftAuditor.ColumnSignature.withoutCharset("WIDGET_NAME", "VARCHAR", 100, 0, false));

        assertTrue(SchemaDriftAuditor.hasDuplicateColumnNames(columns),
                "two signatures sharing a column name must be flagged ambiguous, regardless of their other fields");
    }

    @Test
    void logAmbiguousTablesWarningLogsExactlyOnceRegardlessOfHowManyTablesAreAmbiguous() {
        // Regression test for the per-table Debug.logWarning call this method replaced: on a MySQL
        // server hosting several OFBiz databases, nearly every table can be ambiguous, and this
        // method runs on every SchemaFingerprint.compute/FlywayStrategy call at boot, so a single
        // aggregated warning per invocation is required instead of one warning per table.
        try (MockedStatic<Debug> debugMock = mockStatic(Debug.class)) {
            Set<String> ambiguousTables = new HashSet<>(List.of("PRODUCT", "ORDER_HEADER", "PARTY"));

            SchemaDriftAuditor.logAmbiguousTablesWarning(ambiguousTables);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            debugMock.verify(() -> Debug.logWarning(messageCaptor.capture(), anyString()), times(1));
            String message = messageCaptor.getValue();
            assertTrue(message.contains("3 table(s)"),
                    "aggregated message must state the affected table count: " + message);
            assertTrue(message.contains("PRODUCT") && message.contains("ORDER_HEADER") && message.contains("PARTY"),
                    "aggregated message must list every ambiguous table name: " + message);
        }
    }

    @Test
    void logAmbiguousTablesWarningTruncatesTheListedTableNamesBeyondTheCutoffWithACountSuffix() {
        try (MockedStatic<Debug> debugMock = mockStatic(Debug.class)) {
            Set<String> manyAmbiguousTables = new HashSet<>();
            for (int i = 0; i < 25; i++) {
                manyAmbiguousTables.add("TABLE_" + i);
            }

            SchemaDriftAuditor.logAmbiguousTablesWarning(manyAmbiguousTables);

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            debugMock.verify(() -> Debug.logWarning(messageCaptor.capture(), anyString()), times(1));
            String message = messageCaptor.getValue();
            assertTrue(message.contains("25 table(s)"),
                    "aggregated message must state the total affected table count, even when truncated: " + message);
            assertTrue(message.contains("+5 more"),
                    "aggregated message must summarize the tables beyond the listing cutoff: " + message);
        }
    }

    private boolean hasColumnNamed(Set<SchemaDriftAuditor.ColumnSignature> columns, String columnName) {
        return columns.stream().anyMatch(column -> column.columnName().equals(columnName));
    }

    private void createWidgetTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100))");
        }
    }
}
