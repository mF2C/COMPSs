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
package es.bsc.compss.agent.comm;

import es.bsc.compss.agent.AppMonitor;
import es.bsc.compss.types.annotations.parameter.DataType;


public class PrintMonitor extends AppMonitor {

    public PrintMonitor() {
    }

    @Override
    public void onCreation(long appId, int taskId, Integer coreId) {
        System.out.println("Task created");
    }

    @Override
    public void onAccessesProcessed() {
        System.out.println("Accesses processed");
    }

    @Override
    public void onSchedule() {
        System.out.println("Scheduling");
    }

    @Override
    public void onSubmission() {
        System.out.println("Submitted");
    }

    @Override
    public void onProgress(ProgressUpdate update) {
        System.out.println("Progressing..." + update.toString());
    }

    @Override
    public void valueGenerated(int paramId, String paramName, DataType paramType, String dataId, Object dataLocation) {
        System.out.println("Generated " + paramType + "-value with dataId " + dataId + " at location " + dataLocation
            + " for parameter on position " + paramId + "and  name " + paramName);
    }

    @Override
    public void onAbortedExecution() {
        System.out.println("Execution aborted");
    }

    @Override
    public void onErrorExecution() {
        System.out.println("Error on execution");
    }

    @Override
    public void onFailedExecution() {
        System.out.println("Failed Execution");
    }

    @Override
    public void onSuccesfulExecution() {
        System.out.println("Successful execution");
    }

    @Override
    public void onCancellation() {
        System.out.println("Cancelled");
    }

    @Override
    public void completed() {
        System.out.println("Completed");
    }

    @Override
    public void failed() {
        System.out.println("Failed!");
    }

    @Override
    public void onException() {
        System.out.println("COMPSsException raised");
    }
}
