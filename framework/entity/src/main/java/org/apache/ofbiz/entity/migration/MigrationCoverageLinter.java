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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Two independent pure rules guarding Flyway migration hygiene in a change set.
 *
 * <p>{@link #findViolations}: a change that touches one of a component's entity-model files under
 * {@code entitydef/} — either the canonical {@code entitymodel*.xml} naming, or the
 * {@code <domain>-entitymodel.xml} naming {@code applications/datamodel} uses to centralize other
 * components' models — inside a component that already has {@code migrations/} coverage must also add
 * a new file under that component's {@code migrations/} tree. Prevents silent Entity-Engine/Flyway
 * drift during the transition window before a datasource's auto-DDL can be fully switched off.</p>
 *
 * <p>{@link #findChangedMigrationViolations}: an already-committed migration file must never be
 * touched again — modified, renamed or deleted. Flyway stores the version, description and checksum
 * of every applied migration, so any of those changes makes validation fail on boot for every
 * install that already ran it — the change has to arrive as a new migration instead.</p>
 */
public final class MigrationCoverageLinter {

    /** Matches {@code <componentRoot>/migrations/<vendor>/<name>.sql}. */
    private static final Pattern MIGRATION_FILE = Pattern.compile("^(.+)/migrations/[^/]+/[^/]+\\.sql$",
            Pattern.CASE_INSENSITIVE);

    private MigrationCoverageLinter() {
    }

    public static List<String> findViolations(List<String> changedFilePaths, Set<String> componentRootsWithMigrations) {
        List<String> violations = new ArrayList<>();
        for (String componentRoot : componentRootsWithMigrations) {
            boolean entityModelChanged = changedFilePaths.stream()
                    .anyMatch(path -> isEntityModelFile(path, componentRoot));
            if (!entityModelChanged) {
                continue;
            }
            boolean newMigrationFileIncluded = changedFilePaths.stream()
                    .anyMatch(path -> path.startsWith(componentRoot + "/migrations/"));
            if (!newMigrationFileIncluded) {
                violations.add(componentRoot + ": an entity-model file under entitydef/ changed but no new file was "
                        + "added under " + componentRoot + "/migrations/ — add a migration for this change, "
                        + "or Entity Engine auto-DDL and Flyway history will silently diverge.");
            }
        }
        return violations;
    }

    /**
     * Decides whether a {@code git diff --name-status} change code means anything other than a plain
     * addition. Addition is the one allowed way to introduce a migration; every other status touches a
     * file that may already have been applied somewhere. Rename and copy statuses carry a similarity
     * score suffix ({@code R097}, {@code C085}), so they are matched by their leading letter.
     * @param status a git change status code, e.g. {@code A}, {@code M}, {@code D}, {@code R097}
     * @return {@code true} unless {@code status} is a plain addition
     */
    public static boolean isNonAdditiveChange(String status) {
        if (status == null) {
            return false;
        }
        String trimmed = status.trim();
        return !trimmed.isEmpty() && !"A".equalsIgnoreCase(trimmed);
    }

    /**
     * Flags migration files that were changed in any way other than being added. Independent of
     * {@link #findViolations}: touching an already-applied migration is a violation whether or not any
     * entity model changed alongside it. Renaming one changes its version and description, and deleting
     * one makes Flyway report an applied migration as missing; both fail validation exactly like an
     * in-place edit does.
     * @param changedFilePaths paths whose change status is not Added, i.e. those for which
     *        {@link #isNonAdditiveChange} holds
     * @return one message per offending migration file, empty when there are none
     */
    public static List<String> findChangedMigrationViolations(List<String> changedFilePaths) {
        List<String> violations = new ArrayList<>();
        for (String path : changedFilePaths) {
            if (MIGRATION_FILE.matcher(path).matches()) {
                violations.add(path + ": an already-committed migration file was modified, renamed or deleted — "
                        + "Flyway's version and checksum validation will fail on boot for every install that already "
                        + "applied it. Restore this file as committed and add a new migration instead.");
            }
        }
        return violations;
    }

    /**
     * Recognises any of a component's entity-model definition files, since real OFBiz components split
     * their model across several ({@code entitymodel.xml}, {@code entitymodel_view.xml}, ...).
     * Handles two naming conventions: files that start with {@code entitymodel} (e.g.
     * {@code entitymodel.xml}, {@code entitymodel_view.xml}) and files that end with
     * {@code -entitymodel.xml} (e.g. {@code party-entitymodel.xml}, {@code order-entitymodel.xml}).
     * @param path a change-set path, using {@code /} separators, relative to the repository root
     * @param componentRoot the component's root path, e.g. {@code applications/party}
     * @return {@code true} if {@code path} is an entity-model file directly under {@code entitydef/} of that component
     */
    private static boolean isEntityModelFile(String path, String componentRoot) {
        String entitydefPrefix = componentRoot + "/entitydef/";
        if (!path.startsWith(entitydefPrefix) || !path.endsWith(".xml")) {
            return false;
        }
        String filename = path.substring(entitydefPrefix.length());
        // Must not have subdirectories
        if (filename.contains("/")) {
            return false;
        }
        // Recognise both naming conventions: starts with 'entitymodel' or ends with '-entitymodel.xml'
        return filename.startsWith("entitymodel") || filename.endsWith("-entitymodel.xml");
    }
}
