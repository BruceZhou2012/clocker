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
package brooklyn.networking.sdn.ibm;

import java.net.InetAddress;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.util.net.Cidr;

/**
 * IBM SDN VE configuration and attributes.
 */
public class SdnVeAttributes {

    public static final ConfigKey<InetAddress> GATEWAY = ConfigKeys.newConfigKey(InetAddress.class, "sdn.ibm.network.gateway", "Default gateway for the network segment");

    public static final ConfigKey<Boolean> ENABLE_PUBLIC_ACCESS = ConfigKeys.newBooleanConfigKey("sdn.ibm.public.enable", "Enable external routing for public access", Boolean.FALSE);

    public static final ConfigKey<Cidr> PUBLIC_CIDR = ConfigKeys.newConfigKey(Cidr.class, "sdn.ibm.public.cidr", "Enable external routing");

}
