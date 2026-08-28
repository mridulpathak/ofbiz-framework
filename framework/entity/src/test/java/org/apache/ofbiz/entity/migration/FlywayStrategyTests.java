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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link FlywayStrategy#migrateComponent} — the per-component orchestration the
 * strategy performs at boot — directly against real H2 databases, with temp directories standing
 * in for component roots so no OFBiz component or entity configuration has to be loaded.
 */
public class FlywayStrategyTests {

    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @Test
    void runsMigrationsFoundUnderTheActiveVendorsDirectory(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "vendorhit");

        new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl));

        assertTrue(tableExists(jdbcUrl, "WIDGET"), "V1 should have run for the active vendor");
        assertEquals(1, historyRowCount(jdbcUrl, "flyway_schema_history_example"),
                "history table should be named after the component and hold V1");
    }

    @Test
    void namesTheHistoryTableAfterTheComponentWithHyphensReplaced(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "hyphens");

        new FlywayStrategy().migrateComponent(componentRoot, "my-plugin", "default", target("h2", jdbcUrl));

        assertEquals(1, historyRowCount(jdbcUrl, "flyway_schema_history_my_plugin"));
    }

    @Test
    void noOpsWhenTheComponentHasNoMigrationsAtAll(@TempDir Path tempDir) throws Exception {
        Path componentRoot = Files.createDirectories(tempDir.resolve("nomigrations"));
        String jdbcUrl = jdbcUrl(tempDir, "nomigrations");

        assertDoesNotThrow(() ->
                new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl)));

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
                new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl)));

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
                new FlywayStrategy().migrateComponent(componentRoot, "brokencomponent", "default", target("h2", jdbcUrl)));

        assertTrue(thrown.getMessage().contains("brokencomponent"),
                "the failure must be attributed to the component that caused it, got: " + thrown.getMessage());
    }

    @Test
    void migrateComponentRefusesToRunWhenTheSchemaHasDriftedSinceItsLastFingerprint(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "driftblocked");

        // Stand in for auto-DDL having already created this component's real "example" entity table
        // before Flyway ever recorded a fingerprint for it: resolveComponentTableNames resolves
        // "example" against the REAL entity model (EXAMPLE), not the temp-directory migration used
        // for WIDGET above, so EXAMPLE must actually exist here for the later ALTER TABLE to have
        // something to drift.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE EXAMPLE (EXAMPLE_ID VARCHAR(20) NOT NULL PRIMARY KEY)");
        }

        // First run: migrates cleanly and records a fingerprint.
        new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl));
        assertTrue(tableExists(jdbcUrl, "WIDGET"));

        // Simulate drift: something outside Flyway (e.g. auto-DDL while this datasource was
        // "auto-ddl") added a column to a table this component owns.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE EXAMPLE ADD COLUMN DRIFTED_FIELD VARCHAR(20)");
        }

        ContainerException thrown = assertThrows(ContainerException.class, () ->
                new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl)));

        assertTrue(thrown.getMessage().contains("example"),
                "the failure must be attributed to the component, got: " + thrown.getMessage());
    }

    @Test
    void applySkipsAComponentWhoseEntitiesBelongToADifferentGroup(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "groupmiss");
        // "example" (plugins/example) resolves to org.apache.ofbiz via the default-group fallback
        // (see MigrationSupportTests) - targeting an unrelated group name must skip it entirely.
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "org.apache.ofbiz.olap", "irrelevant-datasource", "h2", jdbcUrl, USER, PASSWORD);
        // A mock stands in for plugins/example's real ComponentConfig: getComponentName() must return
        // "example" (a real, resolvable component) so entity-group resolution reflects reality, but
        // rootLocation() is redirected to this test's temp directory so that, were the group filter to
        // fail to skip this component, migrateComponent would run against real migration SQL and the
        // WIDGET assertion below could actually catch it - unlike loading the real ComponentConfig for
        // "example", whose rootLocation() points at plugins/example (no migrations/h2 directory there),
        // which would make this test pass trivially regardless of whether the group filter works.
        ComponentConfig exampleComponent = mock(ComponentConfig.class);
        when(exampleComponent.getComponentName()).thenReturn("example");
        when(exampleComponent.rootLocation()).thenReturn(componentRoot);

        assertDoesNotThrow(() -> new FlywayStrategy().apply("default", target, List.of(exampleComponent)));

        assertFalse(tableExists(jdbcUrl, "WIDGET"), "a component outside the target's entity group must not be migrated");
    }

    @Test
    void applyMigratesAComponentWhoseEntitiesBelongToTheTargetGroup(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "grouphit");
        // "example" (plugins/example) resolves to org.apache.ofbiz via the default-group fallback
        // (see MigrationSupportTests) - targeting that same group must actually run the migration.
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "org.apache.ofbiz", "irrelevant-datasource", "h2", jdbcUrl, USER, PASSWORD);
        ComponentConfig exampleComponent = mock(ComponentConfig.class);
        when(exampleComponent.getComponentName()).thenReturn("example");
        when(exampleComponent.rootLocation()).thenReturn(componentRoot);

        assertDoesNotThrow(() -> new FlywayStrategy().apply("default", target, List.of(exampleComponent)));

        assertTrue(tableExists(jdbcUrl, "WIDGET"), "a component inside the target's entity group must be migrated");
    }

    @Test
    void applyThrowsWhenAComponentWithMigrationsResolvesNoEntityGroups(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "noentitygroups");
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "org.apache.ofbiz", "irrelevant-datasource", "h2", jdbcUrl, USER, PASSWORD);
        // "party" (applications/party) declares no <entity-resource type="model"> of its own, so
        // resolveComponentEntityGroups genuinely returns an empty set for it against the real, already
        // loaded config (see MigrationSupportTests) - a mock stands in only to redirect rootLocation()
        // at this test's temp directory, which ships a migrations/h2/ directory that apply must refuse
        // to silently skip rather than actually loading plugins/party's real (migration-less) root.
        ComponentConfig partyComponent = mock(ComponentConfig.class);
        when(partyComponent.getComponentName()).thenReturn("party");
        when(partyComponent.rootLocation()).thenReturn(componentRoot);

        ContainerException thrown = assertThrows(ContainerException.class, () ->
                new FlywayStrategy().apply("default", target, List.of(partyComponent)));

        assertTrue(thrown.getMessage().contains("party"),
                "the failure must name the component with unresolvable entity groups, got: " + thrown.getMessage());
    }

    @Test
    void validateThrowsWhenAMigrationHasNeverBeenAppliedOnThisDatasource(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "validatenever");
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "org.apache.ofbiz", "irrelevant-datasource", "h2", jdbcUrl, USER, PASSWORD);
        ComponentConfig exampleComponent = mock(ComponentConfig.class);
        when(exampleComponent.getComponentName()).thenReturn("example");
        when(exampleComponent.rootLocation()).thenReturn(componentRoot);

        ContainerException thrown = assertThrows(ContainerException.class, () ->
                new FlywayStrategy().validate("default", target, List.of(exampleComponent)));

        assertTrue(thrown.getMessage().contains("example"));
    }

    @Test
    void validatePassesWhenEverythingIsAlreadyMigratedAndUnchanged(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "h2",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "validateclean");
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "org.apache.ofbiz", "irrelevant-datasource", "h2", jdbcUrl, USER, PASSWORD);
        ComponentConfig exampleComponent = mock(ComponentConfig.class);
        when(exampleComponent.getComponentName()).thenReturn("example");
        when(exampleComponent.rootLocation()).thenReturn(componentRoot);

        new FlywayStrategy().migrateComponent(componentRoot, "example", "default", target("h2", jdbcUrl));

        assertDoesNotThrow(() -> new FlywayStrategy().validate("default", target, List.of(exampleComponent)));
    }

    @Test
    void validateComponentDoesNotThrowWhenTheComponentHasMigrationsOnlyForAnotherVendor(@TempDir Path tempDir) throws Exception {
        Path componentRoot = componentRootWithMigration(tempDir, "mysql",
                "V1__create_widget.sql", "CREATE TABLE WIDGET (WIDGET_ID VARCHAR(20) NOT NULL PRIMARY KEY);");
        String jdbcUrl = jdbcUrl(tempDir, "validatevendormismatch");

        assertDoesNotThrow(() ->
                new FlywayStrategy().validateComponent(componentRoot, "example", "default", target("h2", jdbcUrl)));
    }

    @Test
    void entitiesNotManagedByThisStrategyExcludesEntitiesOwnedByAComponentWithMigrationsForTheVendor() throws Exception {
        // "Example" is plugins/example's own entity, and plugins/example genuinely has migrations
        // for "h2" (see componentRootWithMigration usage elsewhere in this file) once its migrations
        // directory is on the configured location for it - but this test targets the REAL
        // plugins/example root (no override), which has no migrations/h2 directory today, so this
        // exercises the "not covered" branch for a real entity/component pairing without needing
        // any filesystem setup: a real component with no migrations, feeding a real entity's
        // ModelEntity, must come back in the "not managed" (uncovered) result.
        ModelReader modelReader = ModelReader.getModelReader("default");
        ModelEntity exampleEntity = modelReader.getModelEntity("Example");
        Map<String, ModelEntity> entities = Map.of("Example", exampleEntity);

        Map<String, ModelEntity> notManaged = new FlywayStrategy().entitiesNotManagedByThisStrategy(entities, "h2");

        assertEquals(entities, notManaged, "a real component with no migrations for this vendor must be left for auto-DDL");
    }

    @Test
    void entitiesNotManagedByThisStrategyExcludesAnEntityWhoseComponentHasMigrationsForTheActiveVendor(@TempDir Path tempDir)
            throws Exception {
        String propertyName = "ofbiz.migrations.location.example";
        Path migrationsDir = tempDir.resolve("overridden-migrations");
        Files.createDirectories(migrationsDir.resolve("h2"));
        System.setProperty(propertyName, migrationsDir.toString());
        try {
            ModelReader modelReader = ModelReader.getModelReader("default");
            ModelEntity exampleEntity = modelReader.getModelEntity("Example");
            Map<String, ModelEntity> entities = Map.of("Example", exampleEntity);

            Map<String, ModelEntity> notManaged = new FlywayStrategy().entitiesNotManagedByThisStrategy(entities, "h2");

            assertTrue(notManaged.isEmpty(),
                    "a component with real migrations for the active vendor must be fully covered, got: " + notManaged);
        } finally {
            System.clearProperty(propertyName);
        }
    }

    @Test
    void entitiesNotManagedByThisStrategyLeavesAnEntityWithNoResolvableComponentUncovered() {
        ModelEntity noComponentEntity = new ModelEntity();
        noComponentEntity.setEntityName("NotOwnedByAnyComponent");
        Map<String, ModelEntity> entities = Map.of("NotOwnedByAnyComponent", noComponentEntity);

        Map<String, ModelEntity> notManaged = new FlywayStrategy().entitiesNotManagedByThisStrategy(entities, "h2");

        assertEquals(entities, notManaged, "an entity with no resolvable owning component must be left for auto-DDL");
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

    private static MigrationSupport.JdbcTarget target(String vendor, String jdbcUrl) {
        return new MigrationSupport.JdbcTarget("irrelevant-group", "irrelevant-datasource", vendor, jdbcUrl, USER, PASSWORD);
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
