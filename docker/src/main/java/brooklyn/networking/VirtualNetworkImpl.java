/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
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
package brooklyn.networking;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.render.RendererHints;
import brooklyn.entity.basic.BasicStartableImpl;
import brooklyn.location.Location;
import brooklyn.networking.location.NetworkProvisioningExtension;
import brooklyn.util.net.Cidr;
import brooklyn.util.text.StringFunctions;
import brooklyn.util.text.Strings;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

public class VirtualNetworkImpl extends BasicStartableImpl implements VirtualNetwork {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualNetwork.class);

    @Override
    public void init() {
        LOG.info("Starting virtual network segment id {}", getId());
        super.init();

        String networkId = getConfig(NETWORK_ID);
        if (Strings.isEmpty(networkId)) networkId = getId();
        String networkName = getConfig(NETWORK_NAME);
        if (Strings.isEmpty(networkName)) networkName = networkId;

        setAttribute(NETWORK_ID, networkId);
        setAttribute(NETWORK_NAME, networkName);
        setDisplayName(String.format("%s Network (%s)", Strings.isEmpty(getConfig(NETWORK_NAME)) ? "Virtual" : networkName, networkId));
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        setAttribute(SERVICE_UP, Boolean.FALSE);

        super.start(locations);

        Optional<? extends Location> found = Iterables.tryFind(locations, new Predicate<Location>() {
            @Override
            public boolean apply(Location input) {
                return input.hasExtension(NetworkProvisioningExtension.class);
            }
        });
        if (!found.isPresent()) {
            throw new IllegalStateException("Cannot start a virtual network in any location: " + Iterables.toString(getLocations()));
        }
        NetworkProvisioningExtension provisioner = found.get().getExtension(NetworkProvisioningExtension.class);
        provisioner.provisionNetwork(this);

        setAttribute(SERVICE_UP, Boolean.TRUE);
    }

    @Override
    public void stop() {
        setAttribute(SERVICE_UP, Boolean.FALSE);

        // TODO network detele operations?

        super.stop();
    }

    static {
        RendererHints.register(Cidr.class, RendererHints.displayValue(StringFunctions.toStringFunction()));
    }
}
