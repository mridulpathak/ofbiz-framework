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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class MigrationCoverageLinterTests {

    @Test
    void flagsEntityModelChangeInMigratedComponentWithNoNewMigrationFile() {
        List<String> changedFiles = List.of("applications/party/entitydef/entitymodel.xml");
        Set<String> migratedComponents = Set.of("applications/party");

        List<String> violations = MigrationCoverageLinter.findViolations(changedFiles, migratedComponents);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("applications/party"));
    }

    @Test
    void passesWhenEntityModelChangeIncludesANewMigrationFile() {
        List<String> changedFiles = List.of(
                "applications/party/entitydef/entitymodel.xml",
                "applications/party/migrations/mysql/V2__add_field.sql");
        Set<String> migratedComponents = Set.of("applications/party");

        List<String> violations = MigrationCoverageLinter.findViolations(changedFiles, migratedComponents);

        assertTrue(violations.isEmpty());
    }

    @Test
    void ignoresEntityModelChangeInComponentWithoutMigrationsYet() {
        List<String> changedFiles = List.of("applications/order/entitydef/entitymodel.xml");
        Set<String> migratedComponents = Set.of("applications/party");

        List<String> violations = MigrationCoverageLinter.findViolations(changedFiles, migratedComponents);

        assertTrue(violations.isEmpty(), "component without migrations yet is not covered by this gate");
    }

    @Test
    void flagsChangeToASplitEntityModelFileNotJustTheCanonicalOne() {
        List<String> changedFiles = List.of("applications/party/entitydef/entitymodel_view.xml");
        Set<String> migratedComponents = Set.of("applications/party");

        List<String> violations = MigrationCoverageLinter.findViolations(changedFiles, migratedComponents);

        assertEquals(1, violations.size(), "real components split their model across entitymodel*.xml files");
        assertTrue(violations.get(0).contains("applications/party"));
    }

    @Test
    void flagsChangeToADatamodelStyleEntityModelFile() {
        List<String> changedFiles = List.of("applications/datamodel/entitydef/party-entitymodel.xml");
        Set<String> migratedComponents = Set.of("applications/datamodel");

        List<String> violations = MigrationCoverageLinter.findViolations(changedFiles, migratedComponents);

        assertEquals(1, violations.size(), "datamodel component uses <domain>-entitymodel.xml naming");
        assertTrue(violations.get(0).contains("applications/datamodel"));
    }

    @Test
    void addingANewMigrationFileIsNotAChangedMigrationViolation() {
        // A change set that ADDS a migration alongside an entity-model change: the added migration
        // shows up in the changed-files list but not in the non-additive list, so neither rule fires.
        List<String> changedFiles = List.of(
                "plugins/example/entitydef/entitymodel.xml",
                "plugins/example/migrations/mysql/V2__add_field.sql");
        List<String> nonAdditiveFiles = List.of("plugins/example/entitydef/entitymodel.xml");

        assertTrue(MigrationCoverageLinter.findViolations(changedFiles, Set.of("plugins/example")).isEmpty());
        assertTrue(MigrationCoverageLinter.findChangedMigrationViolations(nonAdditiveFiles).isEmpty(),
                "adding a migration is the correct way to introduce a schema change");
    }

    @Test
    void modifyingAnAlreadyCommittedMigrationFileIsAViolation() {
        List<String> nonAdditiveFiles = List.of("plugins/example/migrations/mysql/V1__baseline.sql");

        List<String> violations = MigrationCoverageLinter.findChangedMigrationViolations(nonAdditiveFiles);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("plugins/example/migrations/mysql/V1__baseline.sql"));
        assertTrue(violations.get(0).contains("checksum"), "message should explain why editing in place breaks installs");
    }

    @Test
    void changedMigrationRuleIgnoresNonMigrationFiles() {
        List<String> nonAdditiveFiles = List.of(
                "plugins/example/entitydef/entitymodel.xml",
                "plugins/example/migrations/README.md",
                "framework/entity/src/main/java/org/apache/ofbiz/entity/migration/ComponentMigrator.java");

        List<String> violations = MigrationCoverageLinter.findChangedMigrationViolations(nonAdditiveFiles);

        assertTrue(violations.isEmpty());
    }

    @Test
    void plainAdditionIsTheOnlyChangeStatusThatIsNotAViolation() {
        assertFalse(MigrationCoverageLinter.isNonAdditiveChange("A"), "adding a migration is always allowed");
        assertFalse(MigrationCoverageLinter.isNonAdditiveChange(""), "a blank status carries no change to judge");
        assertFalse(MigrationCoverageLinter.isNonAdditiveChange(null));
    }

    @Test
    void renameCopyAndDeleteStatusesCountAsNonAdditiveChanges() {
        // git diff --name-status has rename detection on by default, so a renamed migration arrives as
        // R<score> rather than M; renaming an applied migration changes its version and description and
        // deleting one makes Flyway report it as missing, both failing validation just like an edit.
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("M"));
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("D"));
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("R097"), "score-suffixed rename status");
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("R100"));
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("C085"), "score-suffixed copy status");
        assertTrue(MigrationCoverageLinter.isNonAdditiveChange("T"), "type change, e.g. file to symlink");
    }
}
