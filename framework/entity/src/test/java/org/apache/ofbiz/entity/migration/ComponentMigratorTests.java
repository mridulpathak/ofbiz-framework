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
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ComponentMigratorTests {

    @Test
    void migrateAppliesPendingSqlFilesInOrder(@TempDir Path tempDir) throws Exception {
        Path migrationsDir = tempDir.resolve("migrations");
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve("V1__create_widget.sql"),
                "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        Files.writeString(migrationsDir.resolve("V2__add_widget_name.sql"),
                "ALTER TABLE WIDGET ADD COLUMN WIDGET_NAME VARCHAR(100);");

        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + ";DB_CLOSE_DELAY=-1";
        ComponentMigrator migrator = new ComponentMigrator(jdbcUrl, "sa", "", migrationsDir, "flyway_schema_history_widgettest");

        ComponentMigrator.MigrateResult result = migrator.migrate();

        assertEquals(2, result.migrationsExecuted());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'WIDGET' AND COLUMN_NAME = 'WIDGET_NAME'")) {
            assertTrue(rs.next(), "WIDGET_NAME column should exist after both migrations run");
        }
    }

    @Test
    void migrateIsIdempotentOnSecondRun(@TempDir Path tempDir) throws Exception {
        Path migrationsDir = tempDir.resolve("migrations");
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve("V1__create_widget.sql"),
                "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");

        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "idem;DB_CLOSE_DELAY=-1";
        ComponentMigrator migrator = new ComponentMigrator(jdbcUrl, "sa", "", migrationsDir, "flyway_schema_history_widgettest2");

        ComponentMigrator.MigrateResult first = migrator.migrate();
        ComponentMigrator.MigrateResult second = migrator.migrate();

        assertEquals(1, first.migrationsExecuted());
        assertEquals(0, second.migrationsExecuted(), "second run must be a no-op, nothing new pending");
    }

    @Test
    void migrateSucceedsWhenOtherComponentsTablesAlreadyExistButThisComponentHasNoHistoryYet(@TempDir Path tempDir)
            throws Exception {
        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "shared;DB_CLOSE_DELAY=-1";

        // Simulate the real-world, shared-schema situation: other OFBiz components' tables
        // already exist in this database (e.g. from auto-DDL or their own prior migrations),
        // but this component has never migrated here before, so it has no Flyway
        // schema-history table of its own yet.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE UNRELATED_OTHER_COMPONENT_TABLE (ID VARCHAR(20))");
        }

        Path migrationsDir = tempDir.resolve("migrations");
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve("V1__create_widget.sql"),
                "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");

        ComponentMigrator migrator = new ComponentMigrator(jdbcUrl, "sa", "", migrationsDir,
                "flyway_schema_history_widgettest4");

        // Before the fix, Flyway saw a non-empty schema with no history table for this
        // component and refused to migrate, throwing instead of creating WIDGET.
        ComponentMigrator.MigrateResult result = migrator.migrate();

        assertEquals(1, result.migrationsExecuted());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'WIDGET'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "WIDGET table should exist after migrate() runs V1");
        }
    }

    @Test
    void baselineRecordsHistoryWithoutRunningMigrationSql(@TempDir Path tempDir) throws Exception {
        Path migrationsDir = tempDir.resolve("migrations");
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve("V1__create_widget.sql"),
                "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");

        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "baseline;DB_CLOSE_DELAY=-1";
        String historyTable = "flyway_schema_history_widgettest3";
        ComponentMigrator migrator = new ComponentMigrator(jdbcUrl, "sa", "", migrationsDir, historyTable);

        migrator.baseline("1", "baseline");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT \"version\", \"type\", \"success\" FROM \"" + historyTable
                        + "\" WHERE \"type\" = 'BASELINE'")) {
            assertTrue(rs.next(), "schema-history table should contain a BASELINE row");
            assertEquals("1", rs.getString("version"));
            assertEquals("BASELINE", rs.getString("type"));
            assertTrue(rs.getBoolean("success"), "baseline entry should be marked successful");
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'WIDGET'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "baseline must not execute migration SQL, so WIDGET must not exist");
        }
    }
}
