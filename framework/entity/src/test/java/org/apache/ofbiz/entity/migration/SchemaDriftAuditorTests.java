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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void columnNamesOnlySeesTheConfiguredSchemaWhenAnotherSchemaHasATableWithTheSameName(@TempDir Path tempDir) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "schemaqualified;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA TENANT1");
            stmt.execute("CREATE TABLE PUBLIC.EXAMPLE (EXAMPLE_ID VARCHAR(20) NOT NULL PRIMARY KEY)");
            stmt.execute("CREATE TABLE TENANT1.EXAMPLE (EXAMPLE_ID VARCHAR(20) NOT NULL PRIMARY KEY, TENANT_FIELD VARCHAR(20))");

            Set<String> publicColumns = SchemaDriftAuditor.columnNames(conn, "PUBLIC", "EXAMPLE");
            Set<String> tenantColumns = SchemaDriftAuditor.columnNames(conn, "TENANT1", "EXAMPLE");

            assertFalse(publicColumns.contains("TENANT_FIELD"),
                    "PUBLIC.EXAMPLE must not pick up TENANT1.EXAMPLE's column, got: " + publicColumns);
            assertTrue(tenantColumns.contains("TENANT_FIELD"),
                    "TENANT1.EXAMPLE should see its own column, got: " + tenantColumns);
        }
    }

    private void createWidgetTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100))");
        }
    }
}
