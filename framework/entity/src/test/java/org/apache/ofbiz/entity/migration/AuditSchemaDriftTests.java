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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the comparison {@link AuditSchemaDrift} wires together at its core - a component
 * migrated into a scratch database via {@link ComponentMigrator}, compared against a "live"
 * database via {@link SchemaDriftAuditor} - directly against real H2 connections.
 *
 * <p>{@code AuditSchemaDrift.main} itself resolves its live connection through real {@code
 * ComponentConfig}/{@code entityengine.xml} configuration, which is impractical to fully exercise
 * in a unit test (the same reason neither {@link BaselineComponentMigration} nor {@link
 * RunMigrations} has a direct {@code main()}-level test); these tests instead prove the composed
 * logic those CLIs (and this one) all delegate to actually behaves correctly.</p>
 */
public class AuditSchemaDriftTests {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @Test
    void reportsNoDriftWhenTheLiveSchemaMatchesWhatTheRealMigrationsWouldProduce(@TempDir Path tempDir) throws Exception {
        Path migrationsDir = migrationsDirectoryWith(tempDir,
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, "
                        + "WIDGET_NAME VARCHAR(100));");
        String scratchJdbcUrl = jdbcUrl(tempDir, "scratchclean");
        String liveJdbcUrl = jdbcUrl(tempDir, "liveclean");

        // The scratch side: migrated for real, exactly the way AuditSchemaDrift does it.
        new ComponentMigrator(scratchJdbcUrl, USER, PASSWORD, migrationsDir, "flyway_schema_history_example").migrate();

        // The live side: a brownfield install whose schema genuinely matches - created independently,
        // not via Flyway, standing in for a database that was never migrated by this tooling at all.
        try (Connection live = DriverManager.getConnection(liveJdbcUrl, USER, PASSWORD);
                Statement stmt = live.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100))");
        }

        try (Connection liveConn = DriverManager.getConnection(liveJdbcUrl, USER, PASSWORD);
                Connection scratchConn = DriverManager.getConnection(scratchJdbcUrl, USER, PASSWORD)) {
            SchemaDriftAuditor auditor = new SchemaDriftAuditor(liveConn, scratchConn);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertTrue(findings.isEmpty(), "a live schema matching the real migrations exactly must report no drift: "
                    + findings);
        }
    }

    @Test
    void reportsDriftWhenTheLiveSchemaHasAnExtraColumnTheMigrationsNeverAdded(@TempDir Path tempDir) throws Exception {
        Path migrationsDir = migrationsDirectoryWith(tempDir,
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, "
                        + "WIDGET_NAME VARCHAR(100));");
        String scratchJdbcUrl = jdbcUrl(tempDir, "scratchdrift");
        String liveJdbcUrl = jdbcUrl(tempDir, "livedrift");

        new ComponentMigrator(scratchJdbcUrl, USER, PASSWORD, migrationsDir, "flyway_schema_history_example").migrate();

        // Stand in for auto-DDL (or a manual DBA change) having added a column outside Flyway's
        // knowledge - exactly the brownfield-drift scenario this audit exists to catch.
        try (Connection live = DriverManager.getConnection(liveJdbcUrl, USER, PASSWORD);
                Statement stmt = live.createStatement()) {
            stmt.execute("CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY, WIDGET_NAME VARCHAR(100), "
                    + "UNTRACKED_FIELD VARCHAR(50))");
        }

        try (Connection liveConn = DriverManager.getConnection(liveJdbcUrl, USER, PASSWORD);
                Connection scratchConn = DriverManager.getConnection(scratchJdbcUrl, USER, PASSWORD)) {
            SchemaDriftAuditor auditor = new SchemaDriftAuditor(liveConn, scratchConn);
            List<SchemaDriftAuditor.DriftFinding> findings = auditor.findDrift(List.of("WIDGET"));

            assertEquals(1, findings.size());
            assertEquals("WIDGET", findings.get(0).tableName());
            assertTrue(findings.get(0).description().contains("UNTRACKED_FIELD"),
                    "the finding should name the untracked column the live schema drifted by: "
                            + findings.get(0).description());
        }
    }

    private static Path migrationsDirectoryWith(Path tempDir, String fileName, String sql) throws Exception {
        Path migrationsDir = tempDir.resolve("componentroot").resolve("migrations").resolve("h2");
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve(fileName), sql);
        return migrationsDir;
    }

    private static String jdbcUrl(Path tempDir, String suffix) {
        return "jdbc:h2:mem:auditschemadrift" + tempDir.getFileName() + suffix + ";DB_CLOSE_DELAY=-1";
    }
}
