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
package es.bsc.compss.scheduler.loadbalancing;

import es.bsc.compss.components.impl.ResourceScheduler;
import es.bsc.compss.scheduler.loadbalancing.types.LoadBalancingScore;
import es.bsc.compss.scheduler.ready.ReadyScheduler;
import es.bsc.compss.scheduler.types.AllocatableAction;
import es.bsc.compss.scheduler.types.Score;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;

import java.util.List;

import org.json.JSONObject;


/**
 * Representation of a Scheduler that considers only ready tasks and uses resource empty policy.
 */
public class LoadBalancingScheduler extends ReadyScheduler {

    /**
     * Constructs a new LoadBalancingScheduler instance.
     */
    public LoadBalancingScheduler() {
        super();
    }

    /*
     * *********************************************************************************************************
     * *********************************************************************************************************
     * ***************************** UPDATE STRUCTURES OPERATIONS **********************************************
     * *********************************************************************************************************
     * *********************************************************************************************************
     */
    @Override
    public <T extends WorkerResourceDescription> LoadBalancingResourceScheduler<T>
        generateSchedulerForResource(Worker<T> w, Long appId, JSONObject resJSON, JSONObject implJSON) {

        // LOGGER.debug("[LoadBalancingScheduler] Generate scheduler for resource " + w.getName());
        return new LoadBalancingResourceScheduler<>(w, appId, resJSON, implJSON);
    }

    @Override
    public Score generateActionScore(AllocatableAction action) {
        // LOGGER.debug("[LoadBalancingScheduler] Generate Action Score for " + action);
        return new LoadBalancingScore(action.getPriority(), action.getGroupPriority(), 0, -action.getId(), -100);
    }

    /*
     * *********************************************************************************************************
     * *********************************************************************************************************
     * ********************************* SCHEDULING OPERATIONS *************************************************
     * *********************************************************************************************************
     * *********************************************************************************************************
     */
    @Override
    public <T extends WorkerResourceDescription> void purgeFreeActions(List<AllocatableAction> dataFreeActions,
        List<AllocatableAction> resourceFreeActions, List<AllocatableAction> blockedCandidates,
        ResourceScheduler<T> resource) {

        List<AllocatableAction> unassignedReadyActions = this.unassignedReadyActions.removeAllActions();
        dataFreeActions.addAll(unassignedReadyActions);
    }

}
