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
package es.bsc.compss.types;

import es.bsc.compss.api.TaskMonitor;
import es.bsc.compss.types.annotations.parameter.DataType;
import java.util.HashMap;


public class DoNothingTaskMonitor implements TaskMonitor {

    private static final HashMap<Long, TaskMonitor> parentMonitor = new HashMap<>();


    public static void registerParent(long tasksAppId, TaskMonitor monitor) {
        parentMonitor.put(tasksAppId, monitor);
    }

    public static void removeParent(long taskAppId) {
        parentMonitor.remove(taskAppId);
    }


    private long appId;
    private int taskId;
    private Integer coreId;

    private long startTime;
    private long endTime;


    @Override
    public void onCreation(long appId, int taskId, Integer coreId) {
        this.appId = appId;
        this.taskId = taskId;
        this.coreId = coreId;

        TaskMonitor monitor = parentMonitor.get(appId);
        System.out.println("New subtask (" + coreId + ") with id " + taskId + " created for app " + appId);
        monitor.onProgress(new ChildTaskCreated(taskId, coreId));
    }

    @Override
    public void onAccessesProcessed() {
    }

    @Override
    public void onSchedule() {
    }

    @Override
    public void onSubmission() {
        System.out.println("Subtask (" + coreId + ") with id " + taskId + " submitted for app " + appId);
        startTime = System.currentTimeMillis();
    }

    @Override
    public void onProgress(ProgressUpdate update) {
    }

    @Override
    public void valueGenerated(int paramId, String paramName, DataType paramType, String dataId, Object dataLocation) {
    }

    @Override
    public void onAbortedExecution() {
    }

    @Override
    public void onErrorExecution() {
    }

    @Override
    public void onFailedExecution() {
    }

    @Override
    public void onSuccesfulExecution() {
        endTime = System.currentTimeMillis();
    }

    @Override
    public void onCancellation() {
    }

    @Override
    public void onCompletion() {
        TaskMonitor monitor = parentMonitor.get(appId);
        System.out.println("Subtask (" + coreId + ") with id " + taskId + " completed for app " + appId);
        monitor.onProgress(new ChildTaskCompleted(taskId, coreId, endTime - startTime));
    }

    @Override
    public void onFailure() {
        TaskMonitor monitor = parentMonitor.get(appId);
        System.out.println("Subtask (" + coreId + ") with id " + taskId + " failed for app " + appId);
        monitor.onProgress(new ChildTaskCompleted(taskId, coreId, null));
    }

    @Override
    public void onException() {
    }
}
