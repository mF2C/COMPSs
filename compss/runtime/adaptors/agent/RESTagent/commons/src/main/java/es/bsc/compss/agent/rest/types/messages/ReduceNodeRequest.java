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

import es.bsc.compss.types.resources.MethodResourceDescription;
import javax.xml.bind.annotation.XmlRootElement;


@XmlRootElement(name = "reduceNode")
public class ReduceNodeRequest {

    private String workerName;
    private MethodResourceDescription resources;
    private long appId;


    public ReduceNodeRequest() {
    }

    /**
     * Constructs a new ReduceNodeRequest for removing the {@code mrd} resources on node {@code workerName} from the
     * resource pool associated to application {@code appId}.
     *
     * @param workerName name of the node
     * @param mrd resources to remove
     * @param appId id of the application
     */
    public ReduceNodeRequest(String workerName, MethodResourceDescription mrd, Long appId) {
        this.workerName = workerName;
        this.resources = mrd;
        this.appId = appId;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public MethodResourceDescription getResources() {
        return resources;
    }

    public void setResources(MethodResourceDescription resources) {
        this.resources = resources;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

}
