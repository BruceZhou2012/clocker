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
package brooklyn.location.docker;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.render.RendererHints;
import brooklyn.config.render.RendererHints.Hint;
import brooklyn.config.render.RendererHints.NamedActionWithUrl;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.container.docker.DockerAttributes;
import brooklyn.entity.container.docker.DockerCallbacks;
import brooklyn.entity.container.docker.DockerContainer;
import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.PortAttributeSensorAndConfigKey;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.basic.AbstractLocation;
import brooklyn.location.basic.LocationConfigKeys;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.dynamic.DynamicLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.mutex.MutexSupport;
import brooklyn.util.mutex.WithMutexes;
import brooklyn.util.net.Cidr;
import brooklyn.util.os.Os;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.Strings;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;

public class DockerHostLocation extends AbstractLocation implements MachineProvisioningLocation<DockerContainerLocation>, DockerVirtualLocation,
        DynamicLocation<DockerHost, DockerHostLocation>, WithMutexes, Closeable {

    /** serialVersionUID */
    private static final long serialVersionUID = -1453203257759956820L;

    private static final Logger LOG = LoggerFactory.getLogger(DockerHostLocation.class);

    public static final String CONTAINER_MUTEX = "container";

    @SetFromFlag("mutex")
    private transient WithMutexes mutexSupport;

    @SetFromFlag("machine")
    private SshMachineLocation machine;

    @SetFromFlag("jcloudsLocation")
    private JcloudsLocation jcloudsLocation;

    @SetFromFlag("portForwarder")
    private PortForwarder portForwarder;

    @SetFromFlag("owner")
    private DockerHost dockerHost;

    @SetFromFlag("images")
    private ConcurrentMap<String, CountDownLatch> images = Maps.newConcurrentMap();

    public DockerHostLocation() {
        this(Maps.newLinkedHashMap());
    }

    public DockerHostLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    @Override
    public void init() {
        super.init();

        if (mutexSupport == null) {
            mutexSupport = new MutexSupport();
        }
    }

    public DockerContainerLocation obtain() throws NoMachinesAvailableException {
        return obtain(Maps.<String,Object>newLinkedHashMap());
    }

    protected String getImageName(Entity entity, String dockerfile) {
        String simpleName = entity.getEntityType().getSimpleName();
        String version = entity.getConfig(SoftwareProcess.SUGGESTED_VERSION);

        String label = Joiner.on(":").skipNulls().join(simpleName, version, dockerfile);
        return Identifiers.makeIdFromHash(Hashing.md5().hashString(label, Charsets.UTF_8).asLong()).toLowerCase();
    }

    @Override
    public DockerContainerLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
        try {
            acquireMutex(CONTAINER_MUTEX, "Obtaining container");

            // Lookup entity from context or flags
            Object context = flags.get(LocationConfigKeys.CALLER_CONTEXT.getName());
            if (context != null && !(context instanceof Entity)) {
                throw new IllegalStateException("Invalid location context: " + context);
            }
            Entity entity = (Entity) context;

            // Configure the entity
            LOG.info("Configuring entity {} via subnet {}", entity, dockerHost.getSubnetTier());
            ((AbstractEntity) entity).setConfigEvenIfOwned(SubnetTier.PORT_FORWARDING_MANAGER, dockerHost.getSubnetTier().getPortForwardManager());
            ((AbstractEntity) entity).setConfigEvenIfOwned(SubnetTier.PORT_FORWARDER, portForwarder);
            ((AbstractEntity) entity).setConfigEvenIfOwned(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL);
            configureEnrichers((AbstractEntity) entity);

            // Add the entity Dockerfile if configured
            String dockerfile = entity.getConfig(DockerAttributes.DOCKERFILE_URL);
            String imageId = entity.getConfig(DockerAttributes.DOCKER_IMAGE_ID);
            String imageName = getImageName(entity, dockerfile);

            // Lookup image ID or build new image from Dockerfile
            LOG.warn("ImageName for entity {}: {}", entity, imageName);
            String imageList = dockerHost.runDockerCommand("images --no-trunc " + Os.mergePaths("brooklyn", imageName));
            if (Strings.containsLiteral(imageList, imageName)) {
                imageId = Strings.getFirstWordAfter(imageList, "latest");
                LOG.info("Found image {} for entity: {}", imageName, imageId);

                // Wait untill committed before continuing
                ((AbstractEntity) entity).setConfigEvenIfOwned(SoftwareProcess.PRE_INSTALL_COMMAND, DockerCallbacks.image());

                // Skip install phase
                ((AbstractEntity) entity).setConfigEvenIfOwned(SoftwareProcess.SKIP_INSTALLATION, true);
            } else {
                // Set commit command at post-install
                ((AbstractEntity) entity).setConfigEvenIfOwned(SoftwareProcess.POST_INSTALL_COMMAND, DockerCallbacks.commit());

                if (Strings.isNonBlank(dockerfile)) {
                    if (imageId != null) {
                        LOG.warn("Ignoring container imageId {} as dockerfile URL is set: {}", imageId, dockerfile);
                    }
                    imageId = dockerHost.createSshableImage(dockerfile, imageName);
                }
                if (Strings.isBlank(imageId)) {
                    imageId = getOwner().getAttribute(DockerHost.DOCKER_IMAGE_ID);
                }

                // Tag image name and create latch
                images.putIfAbsent(imageName, new CountDownLatch(1));
                dockerHost.runDockerCommand(String.format("tag %s %s:latest", imageId, Os.mergePaths("brooklyn", imageName)));
            }

            // Look up hardware ID
            String hardwareId = entity.getConfig(DockerAttributes.DOCKER_HARDWARE_ID);
            if (Strings.isEmpty(hardwareId)) {
                hardwareId = getOwner().getConfig(DockerAttributes.DOCKER_HARDWARE_ID);
            }

            // Create new Docker container in the host cluster
            LOG.info("Starting container with imageId {} and hardwareId {} at {}", new Object[] { imageId, hardwareId, machine });
            Map<Object, Object> containerFlags = MutableMap.builder()
                    .putAll(flags)
                    .put("entity", entity)
                    .putIfNotNull("imageId", imageId)
                    .putIfNotNull("hardwareId", hardwareId)
                    .build();
            DynamicCluster cluster = dockerHost.getDockerContainerCluster();
            Entity added = cluster.addNode(machine, containerFlags);
            if (added == null) {
                throw new NoMachinesAvailableException(String.format("Failed to create container at %s", dockerHost.getDockerHostName()));
            } else {
                Entities.start(added, ImmutableList.of(machine));
            }

            // Save the container attributes
            DockerContainer dockerContainer = (DockerContainer) added;
            ((EntityLocal) dockerContainer).setAttribute(DockerContainer.IMAGE_ID, imageId);
            ((EntityLocal) dockerContainer).setAttribute(DockerContainer.IMAGE_NAME, imageName);
            ((EntityLocal) dockerContainer).setAttribute(DockerContainer.HARDWARE_ID, hardwareId);
            ((EntityLocal) entity).setAttribute(DockerContainer.CONTAINER, dockerContainer);
            return dockerContainer.getDynamicLocation();
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        } finally {
            releaseMutex(CONTAINER_MUTEX);
        }
    }

    public void waitForImage(String imageName) {
        try {
            CountDownLatch latch = images.get(imageName);
            if (latch != null) latch.await(15, TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        }
    }

    public void markImage(String imageName) {
        CountDownLatch latch = images.get(imageName);
        if (latch != null) latch.countDown();
    }

    private void configureEnrichers(AbstractEntity entity) {
        for (AttributeSensor sensor : Iterables.filter(entity.getEntityType().getSensors(), AttributeSensor.class)) {
            if (DockerAttributes.URL_SENSOR_NAMES.contains(sensor.getName())) {
                AttributeSensor<String> target = DockerAttributes.<String>mappedSensor(sensor);
                entity.addEnricher(dockerHost.getSubnetTier().uriTransformingEnricher(
                        EntityAndAttribute.supplier(entity, sensor), target));
                Set<Hint<?>> hints = RendererHints.getHintsFor(sensor, NamedActionWithUrl.class);
                for (Hint<?> hint : hints) {
                    RendererHints.register(target, (NamedActionWithUrl) hint);
                }
            } else if (PortAttributeSensorAndConfigKey.class.isAssignableFrom(sensor.getClass())) {
                AttributeSensor<String> target = DockerAttributes.mappedPortSensor((PortAttributeSensorAndConfigKey) sensor);
                entity.addEnricher(dockerHost.getSubnetTier().hostAndPortTransformingEnricher(
                        EntityAndAttribute.supplier(entity, sensor), target));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Mapped port sensor: origin={}, mapped={}", sensor.getName(), target.getName());
                }
            }
        }
    }

    @Override
    public void release(DockerContainerLocation machine) {
        try {
            acquireMutex(CONTAINER_MUTEX, "Releasing container " + machine);
            LOG.info("Releasing {}", machine);

            DynamicCluster cluster = dockerHost.getDockerContainerCluster();
            DockerContainer container = machine.getOwner();
            if (cluster.removeMember(container)) {
                LOG.info("Docker Host {}: member {} released", dockerHost.getDockerHostName(), machine);
            } else {
                LOG.warn("Docker Host {}: member {} not found for release", dockerHost.getDockerHostName(), machine);
            }

            // Now close and unmange the container
            try {
                machine.close();
                container.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping container: " + container, e);
                Exceptions.propagateIfFatal(e);
            } finally {
                Entities.unmanage(container);
            }
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        } finally {
            releaseMutex(CONTAINER_MUTEX);
        }
    }

    @Override
    public Map<String, Object> getProvisioningFlags(Collection<String> tags) {
        return MutableMap.of();
    }

    @Override
    public DockerHost getOwner() {
        return dockerHost;
    }

    public SshMachineLocation getMachine() {
        return machine;
    }

    public JcloudsLocation getJcloudsLocation() {
        return jcloudsLocation;
    }

    public PortForwarder getPortForwarder() {
        return portForwarder;
    }

    public int getCurrentSize() {
        return dockerHost.getCurrentSize();
    }

    public int getMaxSize() {
        return dockerHost.getConfig(DockerHost.DOCKER_CONTAINER_CLUSTER_MAX_SIZE);
    }

    @Override
    public MachineProvisioningLocation<DockerContainerLocation> newSubLocation(Map<?, ?> newFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Entity> getDockerContainerList() {
        return dockerHost.getDockerContainerList();
    }

    @Override
    public List<Entity> getDockerHostList() {
        return Lists.<Entity>newArrayList(dockerHost);
    }

    @Override
    public DockerInfrastructure getDockerInfrastructure() {
        return ((DockerLocation) getParent()).getDockerInfrastructure();
    }

    @Override
    public void close() throws IOException {
        LOG.info("Close called on Docker host {}: {}", machine, this);
        try {
            machine.close();
        } catch (Exception e) {
            LOG.info("{}: Closing Docker host: {}", e.getMessage(), this);
            throw Exceptions.propagate(e);
        } finally {
            LOG.info("Docker host closed: {}", this);
        }
    }

    @Override
    public void acquireMutex(String mutexId, String description) throws InterruptedException {
        mutexSupport.acquireMutex(mutexId, description);
    }

    @Override
    public boolean tryAcquireMutex(String mutexId, String description) {
        return mutexSupport.tryAcquireMutex(mutexId, description);
    }

    @Override
    public void releaseMutex(String mutexId) {
        mutexSupport.releaseMutex(mutexId);
    }

    @Override
    public boolean hasMutex(String mutexId) {
        return mutexSupport.hasMutex(mutexId);
    }

    @Override
    public ToStringHelper string() {
        return super.string()
                .add("machine", machine)
                .add("jcloudsLocation", jcloudsLocation)
                .add("dockerHost", dockerHost);
    }

}
