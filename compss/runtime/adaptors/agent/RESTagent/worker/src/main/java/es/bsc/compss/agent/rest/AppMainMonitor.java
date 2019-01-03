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
package es.bsc.compss.agent.rest;

import es.bsc.compss.agent.AgentConstants;
import es.bsc.compss.agent.AppMonitor;
import es.bsc.compss.agent.rest.types.TaskProfile;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.types.resources.components.Processor;
import es.bsc.compss.util.CoreManager;
import es.bsc.mf2c.interaction.ServiceOperationReport;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;


public class AppMainMonitor extends AppMonitor {

    private static final String REPORT_ADDRESS;
    private static final long REPORTING_PERIODICITY = 5_000L;

    private final TaskProfile profile;
    private final ServiceOperationReport report;
    private byte[] result;
    private boolean finished = false;

    private static CoreInfo[] coreInfo = new CoreInfo[CoreManager.getCoreCount()];
    private final Map<Integer, TaskInfo> info = new TreeMap<>();

    static {
        String reportAddress = System.getProperty(AgentConstants.REPORT_ADDRESS);
        if (reportAddress != null && reportAddress.compareTo("null") != 0) {
            REPORT_ADDRESS = reportAddress;
            System.out.println("Reporting executions profiles to " + REPORT_ADDRESS);
        } else {
            REPORT_ADDRESS = null;
        }
        for (int i = 0; i < CoreManager.getCoreCount(); i++) {
            coreInfo[i] = new CoreInfo();
        }
    }


    /**
     * Constructs a new AppMainMonitor for a service operation.
     *
     * @param serviceInstance id of the service instance
     * @param computeNodeId id of the node where the operation is submitted.
     * @param operationName name of the operation
     */
    public AppMainMonitor(String serviceInstance, String computeNodeId, String operationName) {
        super();
        this.profile = new TaskProfile();
        if (REPORT_ADDRESS != null) {
            this.report = new ServiceOperationReport(REPORT_ADDRESS, serviceInstance, computeNodeId, operationName);
        } else {
            this.report = null;
        }
    }

    /**
     * Notifies the monitor the beginning of the operation execution and sets its Id.
     *
     * @param operationId Id of the operation execution.
     */
    public void start(String operationId) {
        if (report != null) {
            report.startOperation(operationId);
            new Thread(new Reporter()).start();
        }
    }

    @Override
    public void onCreation(long appId, int taskId, Integer coreId) {
        profile.created();
    }

    @Override
    public void onAccessesProcessed() {
        profile.processedAccesses();
    }

    @Override
    public void onSchedule() {
        profile.scheduled();
    }

    @Override
    public void onSubmission() {
        profile.submitted();
    }

    @Override
    public void onProgress(ProgressUpdate update) {
        switch (update.getType()) {
            case CHILD_TASK_CREATED: {
                ChildTaskCreated ctc = (ChildTaskCreated) update;
                int coreId = ctc.getCoreId();
                int taskId = ctc.getTaskId();
                if (coreId >= coreInfo.length) {
                    CoreInfo[] newCoreInfo = new CoreInfo[coreId + 1];
                    System.arraycopy(coreInfo, 0, newCoreInfo, 0, coreInfo.length);
                    for (int i = coreInfo.length; i < coreId + 1; i++) {
                        newCoreInfo[i] = new CoreInfo();
                    }
                    coreInfo = newCoreInfo;
                }
                coreInfo[coreId].addTask();
                info.put(taskId, new TaskInfo());
            }
                break;
            case CHILD_TASK_COMPLETED: {
                ChildTaskCompleted ctd = (ChildTaskCompleted) update;
                int taskId = ctd.getTaskId();
                int coreId = ctd.getCoreId();
                Long executionTime = ctd.getExecutionTime();
                System.out.println("subtask of type " + coreId + " completed");
                info.remove(taskId);
                coreInfo[coreId].addExecution(executionTime);
                coreInfo[coreId].removeTask();
                System.out.println(Arrays.toString(coreInfo));
            }
                break;
            case CHILD_TASK_SCHEDULE_UPDATE: {
                ChildTaskUpdate ctu = (ChildTaskUpdate) update;
                TaskInfo ti = info.get(ctu.getTaskId());
                ti.setExpectedEndTime(ctu.getExpectedEndTime());
            }
                break;
        }
        if (report != null) {
            report.progress(predictExpectedEndTime());
        }
    }

    @Override
    public void valueGenerated(int paramId, String paramName, DataType paramType, String dataId, Object dataLocation) {
        LogicalData ld = Comm.getData(dataId);
        if (ld != null) {
            result = (byte[]) ld.getValue();
        }
    }

    @Override
    public void onAbortedExecution() {
        profile.finished();
    }

    @Override
    public void onErrorExecution() {
        profile.finished();
    }

    @Override
    public void onFailedExecution() {
        profile.finished();
    }

    @Override
    public void onException() {
        profile.finished();
    }

    @Override
    public void onSuccesfulExecution() {
        profile.finished();
    }

    @Override
    public void onCancellation() {
        finished = true;
        profile.end();
        System.out.println("Main Job cancelled after " + profile.getTotalTime());
        if (report != null) {
            report.completed(profile.getTotalTime(), null);
        }
    }

    @Override
    public void completed() {
        finished = true;
        profile.end();
        System.out.println("Main Job completed after " + profile.getTotalTime());
        if (report != null) {
            report.completed(profile.getTotalTime(), result);
        }
    }

    @Override
    public void failed() {
        finished = true;
        profile.end();
        System.out.println("Main Job failed after " + profile.getTotalTime());
        if (report != null) {
            report.completed(profile.getTotalTime(), null);
        }
    }

    private long predictExpectedEndTime() {
        long expectedLength = 0;

        int numCPUs = 0;
        for (DynamicMethodWorker worker : this.getNodes()) {
            for (Processor p : worker.getDescription().getProcessors()) {
                numCPUs += p.getComputingUnits();
            }
        }
        // master CPU
        numCPUs--;
        if (numCPUs == 0) {
            return Long.MAX_VALUE;
        }
        for (int coreId = 0; coreId < this.coreInfo.length; coreId++) {
            CoreInfo info = this.coreInfo[coreId];
            int coreCount = info.getTaskCount();
            int rounds = (int) Math.ceil(coreCount / numCPUs);
            long expectedCoreTime = info.getAvgTime();
            System.out.println(
                "Expecting " + rounds + " more rounds of core " + coreId + " with avg time " + expectedCoreTime);
            expectedLength += rounds * expectedCoreTime;
        }
        long now = System.currentTimeMillis();
        long expectedEndTime = now + expectedLength;
        return expectedEndTime;
    }


    private class TaskInfo {

        private long expectedEndTime;


        public void setExpectedEndTime(long expectedEndTime) {
            this.expectedEndTime = expectedEndTime;
        }

        public long getExpectedEndTime() {
            return expectedEndTime;
        }

    }

    private class Reporter implements Runnable {

        @Override
        public void run() {
            if (report != null) {
                while (!finished) {
                    try {
                        Thread.sleep(REPORTING_PERIODICITY);
                    } catch (InterruptedException ie) {
                        // Do nothing, it will report immediately and wait again.
                    }
                    if (!finished) {
                        report.progress(predictExpectedEndTime());
                    }
                }
            }
        }
    }

    private static class CoreInfo {

        private int taskCount;
        private long avgTime;
        private int totalExecutions;


        public int addTask() {
            taskCount++;
            return taskCount;
        }

        public int removeTask() {
            taskCount--;
            return taskCount;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public void addExecution(long time) {
            totalExecutions++;
            avgTime += (time - avgTime) / totalExecutions;
        }

        public long getAvgTime() {
            return avgTime;
        }

    }
}
