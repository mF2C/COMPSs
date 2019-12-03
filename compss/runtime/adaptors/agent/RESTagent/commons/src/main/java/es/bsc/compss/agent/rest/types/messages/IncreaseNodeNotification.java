/*
 *  Copyright 2002-2019 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package es.bsc.compss.agent.rest.types.messages;

import es.bsc.compss.agent.rest.types.ExternalAdaptorResource;
import es.bsc.compss.agent.rest.types.NIOAdaptorResource;
import es.bsc.compss.agent.rest.types.RESTResource;
import es.bsc.compss.agent.types.Resource;
import es.bsc.compss.types.resources.MethodResourceDescription;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;


/**
 * This class contains all the information required to add new resources to the REST Agent. interface.
 */
@XmlRootElement(name = "newResource")
public class IncreaseNodeNotification {

    private Resource<?, ?> resource;
    private Long appId;


    public IncreaseNodeNotification() {
        // Nothing to do.
    }

    /**
     * Constructs a new notification to increase the resources within a node.
     *
     * @param name Name of the resource.
     * @param mrd New resources available on the node.
     * @param adaptor Adaptor to interact with the node.
     * @param resourcesConf Resources.xml configuration for the node.
     * @param projectConf Project.xml configuration for the node.
     * @param appId Id of the application whose tasks can use the resources
     */
    public IncreaseNodeNotification(String name, MethodResourceDescription mrd, String adaptor, Object resourcesConf,
        Object projectConf, Long appId) {

        this.resource = RESTResource.createResource(name, mrd, adaptor, resourcesConf, projectConf);
        this.appId = appId;
    }

    public void setResource(Resource<?, ?> resource) {
        this.resource = resource;
    }

    @XmlElements({ @XmlElement(name = "externalResource", type = ExternalAdaptorResource.class, required = false),
        @XmlElement(name = "nioResource", type = NIOAdaptorResource.class, required = false), })
    public Resource<?, ?> getResource() {
        return this.resource;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public Long getAppId() {
        return this.appId;
    }

}
