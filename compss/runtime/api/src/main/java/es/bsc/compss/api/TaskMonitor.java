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
package es.bsc.compss.api;

import es.bsc.compss.types.annotations.parameter.DataType;


public interface TaskMonitor {

    /**
     * Actions to be performed by monitor on task creation.
     *
     * @param appId application id
     * @param taskId task identifier
     * @param coreId core element id of the task
     */
    public void onCreation(long appId, int taskId, Integer coreId);

    /**
     * Actions to be performed by monitor on data access.
     */
    public void onAccessesProcessed();

    /**
     * Actions to be performed by monitor on task schedule.
     */
    public void onSchedule();

    /**
     * Actions to be performed by monitor on task submission.
     */
    public void onSubmission();

    /**
     * Actions to be performed by the monitor when the scheduler reports the progress of the task execution.
     *
     * @param update description of the progress update
     */
    public void onProgress(ProgressUpdate update);

    /*
     * Actions to be performed by the monitor when a new {@code type}-value, identyfied by the Id {@code dataId}, has
     * been generated at location {@code location} according to the parameter on position {@code paramId} of the task
     * with name {@code paramName}.
     *
     * @param paramId Parameter id.
     * 
     * @param paramName Name of the parameter.
     * 
     * @param paramType Parameter type.
     * 
     * @param dataId Data Management Id.
     * 
     * @param dataLocation Value location.
     */
    public void valueGenerated(int paramId, String paramName, DataType paramType, String dataId, Object dataLocation);

    /**
     * Actions to be performed by monitor on task execution abortion.
     */
    public void onAbortedExecution();

    /**
     * Actions to be performed by monitor on task execution error.
     */
    public void onErrorExecution();

    /**
     * Actions to be performed by monitor on task execution failure.
     */
    public void onFailedExecution();

    /**
     * Actions to be performed by monitor on task execution COMPSs exception.
     */
    public void onException();

    /**
     * Actions to be performed by monitor on task execution success.
     */
    public void onSuccesfulExecution();

    /**
     * Actions to be performed by monitor on task cancellation.
     */
    public void onCancellation();

    /**
     * Actions to be performed by monitor on task completion.
     */
    public void onCompletion();

    /**
     * Actions to be performed by monitor on task failure.
     */
    public void onFailure();


    public static interface ProgressUpdate {

        enum Type {
            CHILD_TASK_CREATED, CHILD_TASK_COMPLETED, CHILD_TASK_SCHEDULE_UPDATE
        }


        Type getType();
    }

    public static class ChildTaskCreated implements ProgressUpdate {

        private final int taskId;
        private final Integer coreId;


        public ChildTaskCreated(int taskId, Integer coreId) {
            this.taskId = taskId;
            this.coreId = coreId;
        }

        @Override
        public Type getType() {
            return Type.CHILD_TASK_CREATED;
        }

        public int getTaskId() {
            return taskId;
        }

        public Integer getCoreId() {
            return coreId;
        }

    }

    public static class ChildTaskCompleted implements ProgressUpdate {

        private final int taskId;
        private final Integer coreId;
        private final Long executionTime;


        /**
         * Constructs a new Child Task Completed to notify the end of task {@code taskId} with coreElement {@code
         * coreId}.
         *
         * @param taskId Id of the completed child task
         * @param coreId Id of the core element of the task
         * @param executionTime elapsed time to run the task
         */
        public ChildTaskCompleted(int taskId, Integer coreId, Long executionTime) {
            this.taskId = taskId;
            this.coreId = coreId;
            this.executionTime = executionTime;
        }

        @Override
        public Type getType() {
            return Type.CHILD_TASK_COMPLETED;
        }

        public int getTaskId() {
            return taskId;
        }

        public Integer getCoreId() {
            return coreId;
        }

        public Long getExecutionTime() {
            return executionTime;
        }

    }

    public static class ChildTaskUpdate implements ProgressUpdate {

        private final int taskId;
        private final long expectedEndTime;


        /**
         * Constructs a new Child Task Update to notify the progress of a child task {@code taskId}.
         *
         * @param taskId updated task id
         * @param expectedEndTime expected end time in millis from January 1970 UTC
         */
        public ChildTaskUpdate(int taskId, long expectedEndTime) {
            this.taskId = taskId;
            this.expectedEndTime = expectedEndTime;
        }

        @Override
        public Type getType() {
            return Type.CHILD_TASK_SCHEDULE_UPDATE;
        }

        public int getTaskId() {
            return taskId;
        }

        public long getExpectedEndTime() {
            return expectedEndTime;
        }

    }
}
