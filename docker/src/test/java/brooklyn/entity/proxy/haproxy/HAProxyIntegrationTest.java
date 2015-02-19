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
package brooklyn.entity.proxy.haproxy;

import static brooklyn.test.HttpTestUtils.assertHttpStatusCodeEventuallyEquals;

import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.container.DockerAttributes;
import brooklyn.entity.container.docker.AbstractClockerIntegrationTest;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.entity.container.docker.DockerInfrastructureTests;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.webapp.JavaWebAppService;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.tomcat.TomcatServer;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.event.basic.Sensors;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.TestResourceUnavailableException;
import brooklyn.test.entity.TestApplication;

public class HAProxyIntegrationTest extends AbstractClockerIntegrationTest {

    private EntitySpec<HAProxyController> haProxySpec() {
        return EntitySpec.create(HAProxyController.class)
                .configure(DockerAttributes.DOCKER_IMAGE_NAME, "haproxy")
                .configure(DockerAttributes.DOCKER_IMAGE_TAG, "latest")
                .configure(SoftwareProcess.INSTALL_DIR, "/usr/local/sbin/")
                .configure(SoftwareProcess.RUN_DIR, "/usr/local/etc/haproxy/");
    }

    private EntitySpec<TomcatServer> tomcatSpec() {
        return EntitySpec.create(TomcatServer.class);
        // Had SSH connection issues when trying to use the configuration below.
        //.configure(DockerAttributes.DOCKER_IMAGE_NAME, "tomcat")
        //.configure(DockerAttributes.DOCKER_IMAGE_TAG, "7-jre7")
        //.configure(SoftwareProcess.INSTALL_DIR, "/usr/local/tomcat/")
        //.configure(SoftwareProcess.EXPANDED_INSTALL_DIR, "/usr/local/tomcat/")
        //.configure(SoftwareProcess.RUN_DIR, "/usr/local/tomcat/");
    }

    private String getTestWar() {
        TestResourceUnavailableException.throwIfResourceUnavailable(getClass(), "/hello-world.war");
        return "classpath://hello-world.war";
    }

    private AttributeSensor<String> mappedSensor(Sensor<String> sensor) {
        return Sensors.newStringSensor("mapped." + sensor.getName());
    }

    @Test(groups = "Integration")
    public void testContainerFromImageName() {
        DockerInfrastructure infrastructure = DockerInfrastructureTests.deployAndWaitForDockerInfrastructure(app, testLocation);

        TestApplication testApp = ApplicationBuilder.newManagedApp(TestApplication.class, app.getManagementContext());
        DynamicCluster serverPool = testApp.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.MEMBER_SPEC, tomcatSpec())
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(JavaWebAppService.ROOT_WAR, getTestWar()));

        HAProxyController hap = testApp.createAndManageChild(haProxySpec()
                .configure(HAProxyController.SERVER_POOL, serverPool)
                .configure(HAProxyController.HOSTNAME_SENSOR, Attributes.SUBNET_HOSTNAME));
        testApp.start(ImmutableList.of(infrastructure.getDynamicLocation()));
        EntityTestUtils.assertAttributeEqualsEventually(hap, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

        // URLs reachable
        assertHttpStatusCodeEventuallyEquals(hap.getAttribute(mappedSensor(HAProxyController.ROOT_URL)), 200);
        for (Entity member : serverPool.getMembers()) {
            assertHttpStatusCodeEventuallyEquals(member.getAttribute(mappedSensor(WebAppService.ROOT_URL)), 200);
        }

        testApp.stop();
    }

}
