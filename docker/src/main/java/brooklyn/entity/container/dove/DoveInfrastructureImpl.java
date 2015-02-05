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
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.render.RendererHints;
import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.BasicStartableImpl;
import brooklyn.entity.basic.DelegateEntity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.feed.ConfigToAttributes;
import brooklyn.location.Location;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.policy.PolicySpec;
import brooklyn.util.collections.QuorumCheck.QuorumChecks;
import brooklyn.util.net.Cidr;
import brooklyn.util.text.Strings;

import com.google.common.collect.ImmutableList;

public class DoveInfrastructureImpl extends BasicStartableImpl implements DoveInfrastructure {

    private static final Logger LOG = LoggerFactory.getLogger(DoveInfrastructureImpl.class);

    private transient Object mutex = new Object[0];

    @Override
    public void init() {
        LOG.info("Starting Weave infrastructure id {}", getId());
        super.init();
        ConfigToAttributes.apply(this, DOCKER_INFRASTRUCTURE);

        EntitySpec<DoveAgent> weaveSpec = EntitySpec.create(getConfig(WEAVE_CONTAINER_SPEC))
                .configure(DoveAgent.WEAVE_CIDR, getConfig(WEAVE_CIDR))
                .configure(DoveAgent.WEAVE_PORT, getConfig(WEAVE_PORT))
                .configure(DoveAgent.WEAVE_INFRASTRUCTURE, this);
        String weaveVersion = getConfig(WEAVE_VERSION);
        if (Strings.isNonBlank(weaveVersion)) {
            weaveSpec.configure(SoftwareProcess.SUGGESTED_VERSION, weaveVersion);
        }

        BasicGroup services = addChild(EntitySpec.create(BasicGroup.class)
                .configure(BasicGroup.RUNNING_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .configure(BasicGroup.UP_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .displayName("Weave Services"));

        if (Entities.isManaged(this)) {
            Entities.manage(services);
        }

        setAttribute(WEAVE_CONTAINER_SPEC, weaveSpec);
        setAttribute(WEAVE_SERVICES, services);
        setAttribute(ALLOCATED_IPS, 0);
    }

    @Override
    public synchronized InetAddress get() {
        Cidr cidr = getConfig(WEAVE_CIDR);
        Integer allocated = getAttribute(ALLOCATED_IPS);
        InetAddress next = cidr.addressAtOffset(allocated + 1);
        setAttribute(ALLOCATED_IPS, allocated + 1);
        return next;
    }

    @Override
    public DynamicCluster getDockerHostCluster() {
        return getConfig(DOCKER_INFRASTRUCTURE).getAttribute(DockerInfrastructure.DOCKER_HOST_CLUSTER);
    }

    @Override
    public Group getWeaveServices() { return getAttribute(WEAVE_SERVICES); }

    public static class MemberTrackingPolicy extends AbstractMembershipTrackingPolicy {
        @Override protected void onEntityEvent(EventType type, Entity member) {
            ((DoveInfrastructureImpl) super.entity).onHostChanged(member);
        }
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        setAttribute(SERVICE_UP, Boolean.FALSE);

        addHostTrackerPolicy();
        super.start(locations);

        setAttribute(SERVICE_UP, Boolean.TRUE);
    }

    @Override
    public void stop() {
        setAttribute(SERVICE_UP, Boolean.FALSE);

        super.stop();
    }

    protected void addHostTrackerPolicy() {
        Group hosts = getDockerHostCluster();
        if (hosts != null) {
            MemberTrackingPolicy hostTrackerPolicy = addPolicy(PolicySpec.create(MemberTrackingPolicy.class)
                    .displayName("Docker host tracker")
                    .configure("group", hosts));
            LOG.info("Added policy {} to {}, during start", hostTrackerPolicy, this);
        }
    }

    protected void onHostAdded(Entity item) {
        synchronized (mutex) {
            SshMachineLocation machine = ((DockerHost) item).getDynamicLocation().getMachine();
            EntitySpec<DoveAgent> spec = EntitySpec.create(getAttribute(WEAVE_CONTAINER_SPEC))
                    .configure(DoveAgent.DOCKER_HOST, (DockerHost) item);
            DoveAgent weave = getWeaveServices().addChild(spec);
            Entities.manage(weave);
            getWeaveServices().addMember(weave);
            weave.start(ImmutableList.of(machine));
            if (LOG.isDebugEnabled()) LOG.debug("{} added weave service {}", this, weave);
        }
    }

    protected void onHostRemoved(Entity item) {
        synchronized (mutex) {
            DoveAgent weave = item.getAttribute(DoveAgent.WEAVE_CONTAINER);
            if (weave == null) {
                LOG.warn("{} cannot find weave service: {}", this, item);
                return;
            }
            weave.stop();
            getWeaveServices().removeMember(weave);
            Entities.unmanage(weave);
            if (LOG.isDebugEnabled()) LOG.debug("{} removed weave service {}", this, weave);
        }
    }

    protected void onHostChanged(Entity item) {
        synchronized (mutex) {
            boolean exists = getDockerHostCluster().hasMember(item);
            Boolean running = item.getAttribute(SERVICE_UP);
            if (exists && running && item.getAttribute(DoveAgent.WEAVE_CONTAINER) == null) {
                onHostAdded(item);
            } else if (!exists) {
                onHostRemoved(item);
            }
        }
    }

    static {
        RendererHints.register(WEAVE_SERVICES, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(DOCKER_INFRASTRUCTURE, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
    }

}
