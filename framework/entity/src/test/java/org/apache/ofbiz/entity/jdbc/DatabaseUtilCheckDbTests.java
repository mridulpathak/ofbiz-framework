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
package org.apache.ofbiz.entity.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.datasource.GenericHelperInfo;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DatabaseUtil#checkDb(Map, Map, List, List, boolean, boolean, boolean, boolean)}'s
 * two-map form directly against real, always-present framework-native entities (framework/entity's
 * own {@code entitymodel_test.xml}, shipped explicitly for unit testing - no dependency on any
 * optional plugin) and a real, isolated in-memory H2 database - no synthetic entities and no mocked
 * JDBC, since the point is proving relation resolution against a genuinely broader reference map
 * than the "manage" map, all the way through to a real {@code ALTER TABLE ... ADD CONSTRAINT}
 * foreign key creation.
 *
 * <p>The scenario reproduces the bug this fixes: {@code Testing} stands in for an entity a different
 * schema-management-strategy (e.g. Flyway) already manages, so it is deliberately excluded from the
 * "manage" map, but its table still genuinely exists in the database (as Flyway would have created it
 * ahead of time). {@code TestingItem} is still managed here and has a {@code type="one"} relation into
 * {@code Testing} (see {@code framework/entity/entitydef/entitymodel_test.xml}, fk-name
 * {@code TESTING_IT_TEST}). Before this fix, that relation's target could never be resolved once
 * {@code Testing} was excluded from the map {@code checkDb} was handed, so the foreign key was
 * silently never created (only a "No such relation" line went to the log - it was never even added
 * to {@code checkDb}'s own {@code messages} output, which is why this test asserts on the foreign
 * key actually being created (a "Created foreign key" message, which {@code checkDb} can only emit
 * for a relation once its target has been resolved), rather than trying to assert on the absence of
 * that log-only line).
 */
class DatabaseUtilCheckDbTests {

    private static final String USER = "ofbiz";
    private static final String PASSWORD = "ofbiz";

    @BeforeEach
    void setOfbizHome() {
        // DatabaseUtil's constructor resolves the h2 field-type-def resource via the "main" resource
        // loader, which requires ofbiz.home; the "test" source set intentionally runs without it set
        // (see build.gradle's sourceSets comment) since most unit tests never need a real
        // ModelFieldTypeReader - matches the established pattern in DelegatorUnitTests.
        System.setProperty("ofbiz.home", System.getProperty("user.dir"));
    }

    @Test
    void checkDbCreatesAForeignKeyWhoseTargetIsOnlyInTheReferenceMapNotTheManageMap() throws Exception {
        ModelReader modelReader = ModelReader.getModelReader("default");
        ModelEntity testingItem = modelReader.getModelEntity("TestingItem");
        ModelEntity testing = modelReader.getModelEntity("Testing");

        // The "manage" map deliberately omits Testing, standing in for Testing being covered by a
        // different schema-management-strategy - exactly the scenario that broke relation resolution.
        Map<String, ModelEntity> manageOnly = new HashMap<>();
        manageOnly.put("TestingItem", testingItem);

        Map<String, ModelEntity> reference = new HashMap<>();
        reference.put("TestingItem", testingItem);
        reference.put("Testing", testing);

        // Reuse the real "localh2" <datasource> element (so schema name, PK/FK style etc. all match
        // production) but redirect it to a private, throwaway in-memory database via a tenant-qualified
        // helper name: DBCPConnectionFactory caches pools keyed by the helper's *full* name, so this can
        // never collide with a pool some other test (or a real boot) already created for plain "localh2",
        // and it never touches the real file-based dev/demo database that plain "localh2" points at.
        String jdbcUrl = "jdbc:h2:mem:checkdbcheckdbtest;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS OFBIZ\\;SET SCHEMA OFBIZ";
        GenericHelperInfo helperInfo = new GenericHelperInfo("org.apache.ofbiz", "localh2");
        helperInfo.setTenantId("checkdbtest");
        helperInfo.setOverrideJdbcUri(jdbcUrl);
        helperInfo.setOverrideUsername(USER);
        helperInfo.setOverridePassword(PASSWORD);
        DatabaseUtil dbUtil = new DatabaseUtil(helperInfo);

        // Stand in for both tables already existing (Testing via the other strategy, TestingItem via
        // this one) with no foreign key between them yet - addFks=false mirrors the "add missing table"
        // call site inside checkDb itself, which never adds FKs at table-creation time either.
        assertNull(dbUtil.createTable(testing, reference, false), "setup: creating the TESTING table must succeed");
        assertNull(dbUtil.createTable(testingItem, reference, false), "setup: creating the TESTING_ITEM table must succeed");

        List<String> messages = new ArrayList<>();
        // checkPks=false, checkFks=true, checkFkIdx=false, addMissing=true: the only relevant check is
        // "does every type=one relation have its foreign key", and, since none exists yet, "create it" -
        // which requires resolving the relation's target entity first.
        dbUtil.checkDb(manageOnly, reference, null, messages, false, true, false, true);

        boolean createdTestingItemToTestingFk = messages.stream()
                .anyMatch(m -> m.startsWith("Created foreign key") && m.contains("for entity [TestingItem]"));
        assertTrue(createdTestingItemToTestingFk,
                "TestingItem's relation to Testing should resolve via the reference map, and its foreign key should "
                        + "be created, even though Testing is absent from the manage map; got messages: " + messages);

        boolean failedToCreateIt = messages.stream()
                .anyMatch(m -> m.contains("Could not create foreign key") && m.contains("TestingItem"));
        assertFalse(failedToCreateIt, "creating the foreign key must not fail; got messages: " + messages);
    }

    @Test
    void checkDbDoesNotFlagATableAsOrphanedWhenItsEntityIsOnlyInTheReferenceMap() throws Exception {
        ModelReader modelReader = ModelReader.getModelReader("default");
        ModelEntity testingItem = modelReader.getModelEntity("TestingItem");
        ModelEntity testing = modelReader.getModelEntity("Testing");

        // The "manage" map deliberately omits Testing, standing in for Testing being covered by a
        // different schema-management-strategy - exactly the scenario that produced false "no
        // corresponding entity" orphan warnings for every Flyway-covered table at boot.
        Map<String, ModelEntity> manageOnly = new HashMap<>();
        manageOnly.put("TestingItem", testingItem);

        Map<String, ModelEntity> reference = new HashMap<>();
        reference.put("TestingItem", testingItem);
        reference.put("Testing", testing);

        String jdbcUrl = "jdbc:h2:mem:checkdbnoorphantest;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS OFBIZ\\;SET SCHEMA OFBIZ";
        GenericHelperInfo helperInfo = new GenericHelperInfo("org.apache.ofbiz", "localh2");
        helperInfo.setTenantId("checkdbnoorphantest");
        helperInfo.setOverrideJdbcUri(jdbcUrl);
        helperInfo.setOverrideUsername(USER);
        helperInfo.setOverridePassword(PASSWORD);
        DatabaseUtil dbUtil = new DatabaseUtil(helperInfo);

        // Both tables genuinely exist - TESTING via the other schema-management-strategy, TESTING_ITEM
        // via this one - so neither is actually an orphan, even though only TestingItem is in manageOnly.
        assertNull(dbUtil.createTable(testing, reference, false), "setup: creating the TESTING table must succeed");
        assertNull(dbUtil.createTable(testingItem, reference, false), "setup: creating the TESTING_ITEM table must succeed");

        List<String> messages = new ArrayList<>();
        dbUtil.checkDb(manageOnly, reference, null, messages, false, false, false, false);

        boolean flaggedTestingAsOrphan = messages.stream()
                .anyMatch(m -> m.contains("no corresponding entity") && m.contains("TESTING]") && !m.contains("TESTING_ITEM]"));
        assertFalse(flaggedTestingAsOrphan,
                "the TESTING table must not be flagged as orphaned just because Testing is absent from the "
                        + "manage map, since it is still accounted for in the broader reference map; got messages: "
                        + messages);
    }

    @Test
    void checkDbStillFlagsAGenuinelyOrphanedTableWhenNoReferenceMapIsSupplied() throws Exception {
        ModelReader modelReader = ModelReader.getModelReader("default");
        ModelEntity testingItem = modelReader.getModelEntity("TestingItem");
        ModelEntity testing = modelReader.getModelEntity("Testing");

        // Same "manage" map as above, but this time Testing is not accounted for anywhere - no
        // reference map is supplied at all, which is today's behavior for every pre-existing caller.
        Map<String, ModelEntity> manageOnly = new HashMap<>();
        manageOnly.put("TestingItem", testingItem);

        Map<String, ModelEntity> allEntities = new HashMap<>();
        allEntities.put("TestingItem", testingItem);
        allEntities.put("Testing", testing);

        String jdbcUrl = "jdbc:h2:mem:checkdbrealorphantest;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS OFBIZ\\;SET SCHEMA OFBIZ";
        GenericHelperInfo helperInfo = new GenericHelperInfo("org.apache.ofbiz", "localh2");
        helperInfo.setTenantId("checkdbrealorphantest");
        helperInfo.setOverrideJdbcUri(jdbcUrl);
        helperInfo.setOverrideUsername(USER);
        helperInfo.setOverridePassword(PASSWORD);
        DatabaseUtil dbUtil = new DatabaseUtil(helperInfo);

        // Both tables genuinely exist, but Testing is absent from every map handed to checkDb below -
        // it should still be flagged as a genuine orphan, proving the fix does not suppress real
        // orphan detection, only the false positive for entities present in a broader reference map.
        assertNull(dbUtil.createTable(testing, allEntities, false), "setup: creating the TESTING table must succeed");
        assertNull(dbUtil.createTable(testingItem, allEntities, false), "setup: creating the TESTING_ITEM table must succeed");

        List<String> messages = new ArrayList<>();
        // The 7-arg form never passes a referenceEntities map at all - it is what every one of the 8
        // pre-existing checkDb call sites ultimately reduces to.
        dbUtil.checkDb(manageOnly, null, messages, false, false, false, false);

        boolean flaggedTestingAsOrphan = messages.stream()
                .anyMatch(m -> m.contains("no corresponding entity") && m.contains("TESTING]") && !m.contains("TESTING_ITEM]"));
        assertTrue(flaggedTestingAsOrphan,
                "the TESTING table must still be flagged as orphaned when it is absent from both the manage "
                        + "map and any reference map; got messages: " + messages);
    }
}
