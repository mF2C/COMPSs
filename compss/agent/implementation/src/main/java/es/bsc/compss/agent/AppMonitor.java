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
package es.bsc.compss.agent;

import es.bsc.compss.api.TaskMonitor;
import es.bsc.compss.types.DoNothingTaskMonitor;
import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.util.ResourceManager;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;


public abstract class AppMonitor implements TaskMonitor {

    private static final HashMap<Long, AppMonitor> APPID_TO_APP_MONITOR = new HashMap<>();


    static AppMonitor getMonitorForApp(Long appId) {
        return APPID_TO_APP_MONITOR.get(appId);
    }


    private Long appId;
    private Long taskAppId = null;
    private final List<DynamicMethodWorker> nodes = new LinkedList<>();


    public AppMonitor() {
    }

    /**
     * Sets the appId related to the monitor.
     *
     * @param appId application id to linked with the monitor
     */
    public void setAppId(long appId) {
        if (this.appId != null) {
            APPID_TO_APP_MONITOR.remove(this.appId);
        }
        this.appId = appId;
        APPID_TO_APP_MONITOR.put(appId, this);
    }

    public long getAppId() {
        return this.appId;
    }

    public void setTaskAppId(long taskAppId) {
        this.taskAppId = taskAppId;
        DoNothingTaskMonitor.registerParent(taskAppId, this);
    }

    public long getTaskAppId() {
        return this.taskAppId;
    }

    public void addNode(DynamicMethodWorker workerNode) {
        this.nodes.add(workerNode);
    }

    protected final List<DynamicMethodWorker> getNodes() {
        return this.nodes;
    }

    @Override
    public final void onCompletion() {
        APPID_TO_APP_MONITOR.remove(appId);
        if (taskAppId != null) {
            DoNothingTaskMonitor.removeParent(taskAppId);
        }
        removeNodes();
        completed();
    }

    public abstract void completed();

    @Override
    public final void onFailure() {
        APPID_TO_APP_MONITOR.remove(appId);
        if (taskAppId != null) {
            DoNothingTaskMonitor.removeParent(taskAppId);
        }
        removeNodes();
        failed();
    }

    public abstract void failed();

    private void removeNodes() {
        for (DynamicMethodWorker workerNode : nodes) {
            ResourceManager.requestWholeWorkerReduction(workerNode);
        }
    }
}
