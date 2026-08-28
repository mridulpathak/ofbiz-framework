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
package org.apache.ofbiz.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public final class SchemaCoverageRegistryTests {

    @AfterEach
    void clearRegistry() {
        SchemaCoverageRegistry.clear();
    }

    @Test
    void lookupReturnsEmptyWhenNothingIsRegisteredForADatasource() {
        Optional<SchemaCoverage> result = SchemaCoverageRegistry.lookup("nothing-registered");

        assertFalse(result.isPresent());
    }

    @Test
    void lookupReturnsWhatWasRegisteredForThatDatasource() {
        SchemaCoverage coverage = (entities, vendor) -> entities;
        SchemaCoverageRegistry.register("mydatasource", coverage);

        Optional<SchemaCoverage> result = SchemaCoverageRegistry.lookup("mydatasource");

        assertTrue(result.isPresent());
        assertSame(coverage, result.get());
    }

    @Test
    void registeringForOneDatasourceDoesNotAffectAnother() {
        SchemaCoverage coverage = (entities, vendor) -> entities;
        SchemaCoverageRegistry.register("datasource-a", coverage);

        assertFalse(SchemaCoverageRegistry.lookup("datasource-b").isPresent());
    }

    @Test
    void registeringAgainForTheSameDatasourceReplacesThePreviousRegistration() {
        SchemaCoverage first = (entities, vendor) -> entities;
        SchemaCoverage second = (entities, vendor) -> Map.of();
        SchemaCoverageRegistry.register("mydatasource", first);
        SchemaCoverageRegistry.register("mydatasource", second);

        assertSame(second, SchemaCoverageRegistry.lookup("mydatasource").get());
    }
}
