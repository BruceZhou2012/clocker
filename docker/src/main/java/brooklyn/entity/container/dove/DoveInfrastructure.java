/*
 * Copyright 2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.entity.container.dove;

import java.net.InetAddress;

import brooklyn.catalog.Catalog;
import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.BasicStartable;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.AttributeSensorAndConfigKey;
import brooklyn.event.basic.Sensors;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.net.Cidr;

import com.google.common.base.Supplier;
import com.google.common.reflect.TypeToken;

/**
 * A collection of machines running Weave.
 */
@Catalog(name = "Weave Infrastructure", description = "Weave SDN.")
@ImplementedBy(DoveInfrastructureImpl.class)
public interface DoveInfrastructure extends BasicStartable, Supplier<InetAddress> {

    ConfigKey<String> WEAVE_VERSION = ConfigKeys.newStringConfigKey("weave.version", "The Weave SDN version number");

    @SetFromFlag("cidr")
    ConfigKey<Cidr> WEAVE_CIDR = DoveAgent.WEAVE_CIDR;

    @SetFromFlag("weavePort")
    ConfigKey<Integer> WEAVE_PORT = DoveAgent.WEAVE_PORT;

    AttributeSensor<Group> WEAVE_SERVICES = Sensors.newSensor(Group.class, "weave.services", "Group of Weave services");
    AttributeSensor<Integer> ALLOCATED_IPS = Sensors.newIntegerSensor("weave.ips", "Number of allocated IPs");

    @SetFromFlag("weaveContainerSpec")
    AttributeSensorAndConfigKey<EntitySpec<DoveAgent>,EntitySpec<DoveAgent>> WEAVE_CONTAINER_SPEC = ConfigKeys.newSensorAndConfigKey(
            new TypeToken<EntitySpec<DoveAgent>>() { },
            "weave.container.spec", "Weave container specification", EntitySpec.create(DoveAgent.class));

    @SetFromFlag("dockerInfrastructure")
    AttributeSensorAndConfigKey<Entity, Entity> DOCKER_INFRASTRUCTURE = DockerHost.DOCKER_INFRASTRUCTURE;

    DynamicCluster getDockerHostCluster();

    Group getWeaveServices();

}
