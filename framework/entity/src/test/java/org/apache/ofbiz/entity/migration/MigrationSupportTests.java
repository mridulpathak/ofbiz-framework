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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MigrationSupportTests {

    @Test
    void resolveSchemaNameReturnsNullWhenTheTargetsDatasourceIsNotARealConfiguredDatasource(@TempDir Path tempDir) throws Exception {
        // A JdbcTarget built with a placeholder datasourceName that isn't in entityengine.xml (the
        // common pattern in this package's own tests) must not blow up - there is no real Datasource
        // config to resolve a schema against, so the safe, correct answer is "unscoped".
        String jdbcUrl = "jdbc:h2:mem:" + tempDir.getFileName() + "resolveschemaname;DB_CLOSE_DELAY=-1";
        MigrationSupport.JdbcTarget target = new MigrationSupport.JdbcTarget(
                "irrelevant-group", "not-a-real-datasource", "h2", jdbcUrl, "sa", "");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            assertNull(MigrationSupport.resolveSchemaName(target, conn));
        }
    }

    @Test
    void resolveComponentEntityGroupsFindsTheDefaultGroupForARealComponent() throws Exception {
        // framework/entity's own test-only entities (Testing, TestingItem, etc. - see
        // entitymodel_test.xml) are not explicitly listed in any <entity-group> mapping, so this
        // exercises the DelegatorElement.getDefaultGroupName() fallback inside
        // ModelGroupReader.getEntityGroupName - real config, a real always-present framework
        // component (no dependency on any optional plugin), no fixtures needed. Component "entity"
        // also contributes some of its own entities (Tenant, Component, etc.) to a second group,
        // org.apache.ofbiz.tenant - this assertion only checks that the default group is among the
        // results, which still holds.
        Set<String> groups = MigrationSupport.resolveComponentEntityGroups("default", "entity");

        assertTrue(groups.contains("org.apache.ofbiz"),
                "framework/entity's own entities should resolve to the default entity group, got: " + groups);
    }

    @Test
    void migrationsDirectoryUsesTheColocatedDefaultWhenNoOverrideIsSet(@TempDir Path tempDir) {
        Path componentRoot = tempDir.resolve("mycomponent");

        Path result = MigrationSupport.migrationsDirectory(componentRoot, "mycomponent-without-override", "mysql");

        assertEquals(componentRoot.resolve("migrations").resolve("mysql"), result);
    }

    @Test
    void migrationsDirectoryUsesTheSystemPropertyOverrideWhenSet(@TempDir Path tempDir) {
        Path componentRoot = tempDir.resolve("mycomponent");
        Path externalRoot = tempDir.resolve("external-migrations-repo");
        String propertyName = "ofbiz.migrations.location.overridetest";
        System.setProperty(propertyName, externalRoot.toString());
        try {
            Path result = MigrationSupport.migrationsDirectory(componentRoot, "overridetest", "mysql");

            assertEquals(externalRoot.resolve("mysql"), result);
        } finally {
            System.clearProperty(propertyName);
        }
    }

    @Test
    void bootstrapComponentsIfNeededDoesNotThrowWhenCalledRepeatedly() {
        // In this test JVM, ComponentConfig is very likely already populated by an unrelated
        // JUnit-wide bootstrap listener (see UelFunctionsBootstrapListener) — this exercises the
        // no-op branch, proving the guard is safe to call unconditionally without duplicating or
        // corrupting an already-loaded cache. RunMigrationsForkedJvmTests covers the from-empty case.
        assertDoesNotThrow(MigrationSupport::bootstrapComponentsIfNeeded);
        assertDoesNotThrow(MigrationSupport::bootstrapComponentsIfNeeded);
    }
}
