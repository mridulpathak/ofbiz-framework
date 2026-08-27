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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Proves {@link RunMigrations} actually sees real component data when launched exactly the way
 * the {@code runMigrations} Gradle task launches it: as a bare {@code java -cp ... RunMigrations
 * <args>} forked process. Every other test in this package runs this package's logic in-process,
 * where {@code ComponentConfig}'s cache happens to already be populated by an unrelated JUnit
 * {@code LauncherSessionListener} (see {@code UelFunctionsBootstrapListener} in
 * {@code framework/base}) — that side effect does not exist in a real forked {@code javaexec},
 * which is never touched by JUnit Platform's Launcher SPI at all. This is the one test in this
 * package that reproduces the real deployment path.
 */
class RunMigrationsForkedJvmTests {

    @Test
    void runMigrationsSeesRealComponentsWhenLaunchedAsAForkedProcess() throws Exception {
        Path ofbizHome = Paths.get("").toAbsolutePath().normalize();
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        Process process = new ProcessBuilder(javaBin, "-cp", classpath,
                "-Dofbiz.home=" + ofbizHome,
                "org.apache.ofbiz.entity.migration.RunMigrations", "default")
                .redirectErrorStream(true)
                .start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, "runMigrations should exit cleanly; full output:\n" + output);
        assertTrue(output.contains("[runMigrations] Resolved ") && !output.contains("Resolved 0 component"),
                "expected the forked process to see real, non-zero component data (this fails today because "
                        + "ComponentConfig's cache is never bootstrapped standalone); full output:\n" + output);
    }
}
