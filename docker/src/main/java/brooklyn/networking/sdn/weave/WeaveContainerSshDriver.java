/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 */
package brooklyn.networking.sdn.weave;

import java.net.InetAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractGroup;
import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.networking.sdn.SdnAgent;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Cidr;
import brooklyn.util.os.Os;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.task.Tasks;

import com.google.common.collect.Lists;

public class WeaveContainerSshDriver extends AbstractSoftwareProcessSshDriver implements WeaveContainerDriver {

    private static final Logger LOG = LoggerFactory.getLogger(WeaveContainer.class);

    public WeaveContainerSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    public String getWeaveCommand() {
        return Os.mergePathsUnix(getInstallDir(), "weave");
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this);
    }

    @Override
    public void install() {
        List<String> commands = Lists.newLinkedList();
        commands.addAll(BashCommands.commandsToDownloadUrlsAs(resolver.getTargets(), getWeaveCommand()));
        commands.add("chmod 755 " + getWeaveCommand());

        newScript(INSTALLING)
                .body.append(commands)
                .execute();
    }

    @Override
    public void customize() {
        newScript(CUSTOMIZING).execute();
    }

    @Override
    public void launch() {
        InetAddress address = getEntity().getAttribute(WeaveContainer.SDN_AGENT_ADDRESS);
        Boolean firstMember = getEntity().getAttribute(AbstractGroup.FIRST_MEMBER);
        Entity first = getEntity().getAttribute(AbstractGroup.FIRST);
        LOG.info("Launching {} Weave service at {}", Boolean.TRUE.equals(firstMember) ? "first" : "next", address.getHostAddress());

        newScript(MutableMap.of(USE_PID_FILE, false), LAUNCHING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(BashCommands.sudo(String.format("%s launch %s", getWeaveCommand(),
                        Boolean.TRUE.equals(firstMember) ? "" : first.getAttribute(Attributes.SUBNET_ADDRESS))))
                .execute();
    }

    @Override
    public boolean isRunning() {
        // Spawns a container for duration of command, so take the host lock
        getEntity().getAttribute(SdnAgent.DOCKER_HOST).getDynamicLocation().getLock().lock();
        try {
            return newScript(MutableMap.of(USE_PID_FILE, false), CHECK_RUNNING)
                    .body.append(BashCommands.sudo(getWeaveCommand() + " status"))
                    .execute() == 0;
        } finally {
            getEntity().getAttribute(SdnAgent.DOCKER_HOST).getDynamicLocation().getLock().unlock();
        }
    }

    @Override
    public void stop() {
        newScript(MutableMap.of(USE_PID_FILE, false), STOPPING)
                .body.append(BashCommands.sudo(getWeaveCommand() + " stop"))
                .execute();
    }

    @Override
    public void createSubnet(String svirtualNetworkId, String subnetId, Cidr subnetCidr) {
        LOG.debug("Nothing to do for Weave subnet creation");
    }

    @Override
    public InetAddress attachNetwork(String containerId, String subnetId) {
        Tasks.setBlockingDetails(String.format("Attach %s to %s", containerId, subnetId));
        try {
            Cidr cidr = getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getSubnetCidr(subnetId);
            InetAddress address = getEntity().getAttribute(SdnAgent.SDN_PROVIDER).getNextContainerAddress(subnetId);
            ((WeaveContainer) getEntity()).getDockerHost().execCommand(BashCommands.sudo(String.format("%s attach %s/%d %s",
                    getWeaveCommand(), address.getHostAddress(), cidr.getLength(), containerId)));
            return address;
        } finally {
            Tasks.resetBlockingDetails();
        }
    }

}
