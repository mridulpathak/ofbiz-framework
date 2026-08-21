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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.ofbiz.base.container.ContainerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link MigrationContainer#migrateComponent} — the per-component orchestration the
 * container performs at boot — directly against real H2 databases, with temp directories standing
 * in for component roots so no OFBiz component or entity configuration has to be loaded.
 */
public class MigrationContainerTests {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @Test
    void runsMigrationsFoundUnderTheActiveVendorsDirectory(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "vendorhit");

        new MigrationContainer().migrateComponent(componentRoot, "example", "h2", jdbcUrl, USER, PASSWORD);

        assertTrue(tableExists(jdbcUrl, "WIDGET"), "V1 should have run for the active vendor");
        assertEquals(1, historyRowCount(jdbcUrl, "flyway_schema_history_example"),
                "history table should be named after the component and hold V1");
    }

    @Test
    void namesTheHistoryTableAfterTheComponentWithHyphensReplaced(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "hyphens");

        new MigrationContainer().migrateComponent(componentRoot, "my-plugin", "h2", jdbcUrl, USER, PASSWORD);

        assertEquals(1, historyRowCount(jdbcUrl, "flyway_schema_history_my_plugin"));
    }

    @Test
    void noOpsWhenTheComponentHasNoMigrationsAtAll(@TempDir Path tempDir) throws Exception {
        Path componentRoot = Files.createDirectories(tempDir.resolve("nomigrations"));
        String jdbcUrl = jdbcUrl(tempDir, "nomigrations");

        assertDoesNotThrow(() ->
                new MigrationContainer().migrateComponent(componentRoot, "example", "h2", jdbcUrl, USER, PASSWORD));

        assertFalse(tableExists(jdbcUrl, "WIDGET"));
        // Flyway creates its history table with the exact (quoted, lowercase) name it was given -
        // matching case is what makes this assertion able to actually fail, see historyRowCount below.
        assertFalse(tableExists(jdbcUrl, "flyway_schema_history_example"),
                "a component with no migrations must not get a history table");
    }

    @Test
    void noOpsWhenTheComponentShipsMigrationsButNotForTheActiveVendor(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "mysql",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "vendormiss");

        // The dangerous-but-not-fatal case: this component is migrated, just not for the vendor
        // currently active. It must be skipped (and warned about) rather than failing the boot.
        assertDoesNotThrow(() ->
                new MigrationContainer().migrateComponent(componentRoot, "example", "h2", jdbcUrl, USER, PASSWORD));

        assertFalse(tableExists(jdbcUrl, "WIDGET"), "the mysql migration must not run against an h2 datasource");
        assertFalse(tableExists(jdbcUrl, "flyway_schema_history_example"),
                "no history table should be created for a vendor this component has no migrations for");
    }

    @Test
    void wrapsAFailingMigrationInAContainerExceptionNamingTheComponent(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__broken.sql", "THIS IS NOT VALID SQL AT ALL;");
        String jdbcUrl = jdbcUrl(tempDir, "broken");

        // ContainerException (checked) rather than RuntimeException, so ContainerLoader can turn it
        // into a StartupException and shut OFBiz down cleanly instead of dying mid-boot.
        ContainerException thrown = assertThrows(ContainerException.class, () ->
                new MigrationContainer().migrateComponent(componentRoot, "brokencomponent", "h2", jdbcUrl, USER, PASSWORD));

        assertTrue(thrown.getMessage().contains("brokencomponent"),
                "the failure must be attributed to the component that caused it, got: " + thrown.getMessage());
    }

    private static Path componentRootWithMigration(Path tempDir, String vendor, String fileName, String sql) throws Exception {
        Path migrationsDir = tempDir.resolve("componentroot").resolve("migrations").resolve(vendor);
        Files.createDirectories(migrationsDir);
        Files.writeString(migrationsDir.resolve(fileName), sql);
        return tempDir.resolve("componentroot");
    }

    private static String jdbcUrl(Path tempDir, String suffix) {
        return "jdbc:h2:mem:migcontainer" + tempDir.getFileName() + suffix + ";DB_CLOSE_DELAY=-1";
    }

    // Callers must pass the table's exact case: unquoted DDL (e.g. "WIDGET") is uppercased by H2, but
    // Flyway's own history table is quoted and created with the exact case it was given (lowercase
    // here) - INFORMATION_SCHEMA.TABLES.TABLE_NAME preserves whichever case actually got created.
    private static boolean tableExists(String jdbcUrl, String tableName) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '"
                        + tableName + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static int historyRowCount(String jdbcUrl, String historyTable) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + historyTable + "\" WHERE \"type\" = 'SQL'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
