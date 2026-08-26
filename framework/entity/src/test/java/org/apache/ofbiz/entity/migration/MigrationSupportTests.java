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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

public class MigrationSupportTests {

    @Test
    void resolveComponentEntityGroupsFindsTheDefaultGroupForARealComponent() throws Exception {
        // plugins/example's entities are not explicitly listed in any <entity-group> mapping, so
        // this exercises the DelegatorElement.getDefaultGroupName() fallback inside
        // ModelGroupReader.getEntityGroupName - real config, real component, no fixtures needed.
        Set<String> groups = MigrationSupport.resolveComponentEntityGroups("default", "example");

        assertTrue(groups.contains("org.apache.ofbiz"),
                "plugins/example's entities should resolve to the default entity group, got: " + groups);
    }
}
