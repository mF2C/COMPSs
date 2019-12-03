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
package es.bsc.compss.components.impl;

import es.bsc.compss.log.Loggers;
import es.bsc.compss.scheduler.exceptions.ActionNotFoundException;
import es.bsc.compss.scheduler.exceptions.ActionNotWaitingException;
import es.bsc.compss.scheduler.types.AllocatableAction;
import es.bsc.compss.scheduler.types.Profile;
import es.bsc.compss.scheduler.types.Score;
import es.bsc.compss.types.CoreElement;
import es.bsc.compss.types.TaskDescription;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;
import es.bsc.compss.types.resources.updates.ResourceUpdate;
import es.bsc.compss.util.CoreManager;
import es.bsc.compss.util.ErrorManager;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Scheduler representation for a given worker.
 *
 * @param <T> WorkerResourceDescription.
 */
public class ResourceScheduler<T extends WorkerResourceDescription> {

    // Logger
    protected static final Logger LOGGER = LogManager.getLogger(Loggers.TS_COMP);
    protected static final boolean DEBUG = LOGGER.isDebugEnabled();

    // Identifier of the only application that can use the resource
    private final Long assignedAppId;

    // Task running in the resource
    private final List<AllocatableAction> running;
    // Task without enough resources to be executed right now
    protected final PriorityQueue<AllocatableAction> blocked;

    // Worker assigned to the resource scheduler
    protected final Worker<T> myWorker;
    // Modifications pending to be applied
    private final List<ResourceUpdate<T>> pendingModifications;

    // Profile information of the task executions
    private Profile[][] profiles;

    private boolean removed = false;


    /**
     * Constructs a new Resource Scheduler associated to the worker {@code w}.
     *
     * @param w Associated worker.
     * @param appId Id of the application whose tasks can use the resources
     * @param defaultResource JSON description of the resource.
     * @param defaultImplementations JSON description of the implementations.
     */
    public ResourceScheduler(Worker<T> w, Long appId, JSONObject defaultResource, JSONObject defaultImplementations) {
        this.assignedAppId = appId;
        this.running = new LinkedList<>();
        this.blocked = new PriorityQueue<>(20, new Comparator<AllocatableAction>() {

            @Override
            public int compare(AllocatableAction a1, AllocatableAction a2) {
                Score score1 = generateBlockedScore(a1);
                Score score2 = generateBlockedScore(a2);
                return score1.compareTo(score2);
            }
        });

        this.myWorker = w;
        this.pendingModifications = new LinkedList<>();
        JSONObject resMap;
        if (defaultResource != null) {
            try {
                resMap = defaultResource.getJSONObject("implementations");
            } catch (JSONException je) {
                resMap = null;
            }
        } else {
            resMap = null;
        }
        this.profiles = loadProfiles(resMap, defaultImplementations);

    }

    /*
     * ***************************************************************************************************************
     * RESOURCE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Returns the worker name.
     *
     * @return The worker name.
     */
    public final String getName() {
        return this.myWorker.getName();
    }

    /**
     * Returns the id of the only application whose tasks can run on the resource.
     *
     * @return id of the only application whose tasks can run on the resource
     */
    public Long getAssignedAppId() {
        return this.assignedAppId;
    }

    /**
     * Returns the worker resource.
     *
     * @return The worker resource.
     */
    public final Worker<T> getResource() {
        return this.myWorker;
    }

    /**
     * Returns the coreElements that can be executed by the resource.
     *
     * @return The coreElements that can be executed by the resource.
     */
    public final List<Integer> getExecutableCores() {
        return this.myWorker.getExecutableCores();
    }

    /**
     * Returns the implementations that can be executed by the resource.
     *
     * @return The implementations that can be executed by the resource.
     */
    public final List<Implementation>[] getExecutableImpls() {
        return this.myWorker.getExecutableImpls();
    }

    /**
     * Returns the implementations of the core {@code id} that can be executed by the resource.
     *
     * @param coreId Core Id.
     * @return The implementations of the core {@code id} that can be executed by the resource.
     */
    public final List<Implementation> getExecutableImpls(int coreId) {
        return this.myWorker.getExecutableImpls(coreId);
    }

    /**
     * Adds a pending modification on the resource features.
     *
     * @param modification Pending modification.
     */
    public final void pendingModification(ResourceUpdate<T> modification) {
        this.pendingModifications.add(modification);
    }

    /**
     * Returns whether there are pending modifications on the resource or not.
     *
     * @return {@literal true} if there are pending modifications, {@literal false} otherwise.
     */
    public final boolean hasPendingModifications() {
        return !this.pendingModifications.isEmpty();
    }

    /**
     * Returns the pending modifications on the resource.
     *
     * @return The pending modifications on the resource.
     */
    public final List<ResourceUpdate<T>> getPendingModifications() {
        return this.pendingModifications;
    }

    /**
     * Removes a pending modification on the resource features.
     *
     * @param modification Modification to remove.
     */
    public final void completedModification(ResourceUpdate<T> modification) {
        this.pendingModifications.remove(modification);
    }

    /*
     * ***************************************************************************************************************
     * ACTION PROFILE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Prepares the default profiles for each implementation cores.
     *
     * @param resMap default profile values for the resource.
     * @param implMap default profile values for the implementation.
     * @return default profile structure.
     */
    protected final Profile[][] loadProfiles(JSONObject resMap, JSONObject implMap) {
        Profile[][] profiles;
        int coreCount = CoreManager.getCoreCount();
        profiles = new Profile[coreCount][];
        for (CoreElement ce : CoreManager.getAllCores()) {
            int coreId = ce.getCoreId();
            List<Implementation> impls = ce.getImplementations();
            int implCount = impls.size();
            profiles[coreId] = new Profile[implCount];
            for (Implementation impl : impls) {
                String signature = impl.getSignature();
                JSONObject jsonImpl = null;
                if (resMap != null) {
                    try {
                        jsonImpl = resMap.getJSONObject(signature);
                        profiles[coreId][impl.getImplementationId()] = generateProfileForImplementation(impl, jsonImpl);
                    } catch (JSONException je) {
                        // Do nothing
                    }
                }
                if (profiles[coreId][impl.getImplementationId()] == null) {
                    if (implMap != null) {
                        try {
                            jsonImpl = implMap.getJSONObject(signature);
                        } catch (JSONException je) {
                            // Do nothing
                        }
                    }
                    profiles[coreId][impl.getImplementationId()] = generateProfileForImplementation(impl, jsonImpl);
                    profiles[coreId][impl.getImplementationId()].clearExecutionCount();
                }
            }
        }
        return profiles;
    }

    /**
     * Updates the coreElement structures.
     *
     * @param newCoreCount New core count.
     * @param resourceJSON JSON description of the implementations.
     */
    public void updatedCoreElements(int newCoreCount, JSONObject resourceJSON) {
        int oldCoreCount = this.profiles.length;
        Profile[][] profiles = new Profile[newCoreCount][];
        JSONObject implMap;
        if (resourceJSON != null) {
            try {
                implMap = resourceJSON.getJSONObject("implementations");
            } catch (JSONException je) {
                implMap = null;
            }
        } else {
            implMap = null;
        }

        for (CoreElement ce : CoreManager.getAllCores()) {
            int coreId = ce.getCoreId();
            int oldImplCount = 0;
            if (coreId < oldCoreCount) {
                oldImplCount = this.profiles[coreId].length;
            }
            List<Implementation> impls = ce.getImplementations();
            int newImplCount = impls.size();

            // Create new array
            profiles[coreId] = new Profile[newImplCount];

            // Copy the previous profile implementations
            for (Implementation impl : impls) {
                int implId = impl.getImplementationId();
                if (implId < oldImplCount) {
                    profiles[coreId][implId] = this.profiles[coreId][implId];
                } else {
                    JSONObject jsonImpl;
                    if (implMap != null) {
                        try {
                            jsonImpl = implMap.getJSONObject(impl.getSignature());
                        } catch (JSONException je) {
                            jsonImpl = null;
                        }
                    } else {
                        jsonImpl = null;
                    }
                    profiles[coreId][implId] = generateProfileForImplementation(impl, jsonImpl);
                }
            }
        }
        this.profiles = profiles;
    }

    /**
     * Generates a Profile for an action.
     *
     * @param impl Implementation.
     * @param jsonImpl JSON representation of the implementation.
     * @return a profile object for an action.
     */
    public Profile generateProfileForImplementation(Implementation impl, JSONObject jsonImpl) {
        return new Profile(jsonImpl);
    }

    /**
     * Generates a Profile for an action.
     *
     * @param <R> WorkerResourceDescription.
     * @param action Allocatable Action.
     * @return A profile object for an action.
     */
    public <R extends WorkerResourceDescription> Profile generateProfileForRun(AllocatableAction action) {
        return new Profile();
    }

    /**
     * Returns the profile for a given implementation {@code impl}.
     *
     * @param coreId Core Id.
     * @param implId Implementation id.
     * @return Associated profile.
     */
    public final Profile getProfile(int coreId, int implId) {
        return profiles[coreId][implId];
    }

    /**
     * Returns the profile for a given implementation {@code impl}.
     *
     * @param impl Implementation.
     * @return Associated profile.
     */
    public final Profile getProfile(Implementation impl) {
        if (impl != null) {
            if (impl.getCoreId() != null) {
                return getProfile(impl.getCoreId(), impl.getImplementationId());
            }
        }
        return null;
    }

    /**
     * Updates the execution profile of implementation {@code impl} by accumulating the profile {@code profile}.
     *
     * @param impl Implementation.
     * @param profile Profile to update.
     */
    public final void profiledExecution(Implementation impl, Profile profile) {
        if (impl != null) {
            int coreId = impl.getCoreId();
            int implId = impl.getImplementationId();
            this.profiles[coreId][implId].accumulate(profile);
        }

    }

    /*
     * ***************************************************************************************************************
     * RUNNING ACTIONS MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Returns the number of tasks of type {@code taskId} that this resource is running.
     *
     * @param coreId Core Id.
     * @return Number of tasks of the given core Id.
     */
    public final int getNumTasks(int coreId) {
        int taskCount = -1;
        if (coreId < profiles.length) {
            taskCount = 0;
            for (AllocatableAction aa : this.running) {
                if (aa.getCoreId() == coreId) {
                    taskCount++;
                }
            }
        }
        return taskCount;
    }

    /**
     * Returns true if this resource has available slots to run some task. False otherwise.
     *
     * @return
     */
    public final boolean canRunSomething() {
        return this.myWorker.canRunSomething();
    }

    /**
     * Returns all the hosted actions.
     *
     * @return All the hosted actions.
     */
    public final AllocatableAction[] getHostedActions() {
        return this.running.toArray(new AllocatableAction[running.size()]);
    }

    /**
     * Adds a new running action on the resource.
     *
     * @param action AllocatableAction to add to the resource.
     */
    public final void hostAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Host action " + action);
        this.running.add(action);

    }

    /**
     * Removes a running action.
     *
     * @param action AllocatableAction to remove from the resource.
     */
    public final void unhostAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Unhost action " + action + " on resource " + getName());
        this.running.remove(action);
    }

    /*
     * ***************************************************************************************************************
     * BLOCKED ACTIONS MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Adds a blocked action on this worker.
     *
     * @param action Blocked AllocatableAction.
     */
    public final void waitOnResource(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Block action " + action + " on resource " + getName());
        if (!removed) {
            this.blocked.add(action);
        } else {
            LOGGER.warn("[ResourceScheduler] Blocked action " + action + " on removed resource " + getName()
                + ". Trying to reschedule... ");
            try {
                unscheduleAction(action);
                action.schedule(generateBlockedScore(action));
            } catch (Exception e) {
                ErrorManager.error("Error rescheduling action to a removed resource", e);
            }
        }
    }

    /**
     * Returns whether there are blocked actions or not.
     *
     * @return {@literal true} if there are blocked actions, {@literal false} otherwise.
     */
    public final boolean hasBlockedActions() {
        return !this.blocked.isEmpty();
    }

    /**
     * Returns all the blocked actions.
     *
     * @return All the blocked actions.
     */
    public PriorityQueue<AllocatableAction> getBlockedActions() {
        return this.blocked;
    }

    /**
     * Returns the first blocked action without removing it.
     *
     * @return The first blocked action.
     */
    public final AllocatableAction getFirstBlocked() {
        return this.blocked.peek();
    }

    /**
     * Removes the first blocked action.
     */
    public final void removeFirstBlocked() {
        this.blocked.poll();
    }

    /**
     * Tries to launch blocked actions on resource. When an action cannot be launched, its successors are not tried
     */
    @SuppressWarnings("unchecked")
    public final void tryToLaunchBlockedActions() {
        LOGGER.debug("[ResourceScheduler] Try to launch blocked actions on resource " + getName());
        while (this.hasBlockedActions()) {
            AllocatableAction firstBlocked = this.getFirstBlocked();
            Implementation selectedImplementation = firstBlocked.getAssignedImplementation();
            if (!firstBlocked.isToReserveResources()
                || myWorker.canRunNow((T) selectedImplementation.getRequirements())) {
                try {
                    firstBlocked.resumeExecution();
                    this.removeFirstBlocked();
                } catch (ActionNotWaitingException anwe) {
                    // Not possible. If the task is in blocked list it is waiting
                }
            } else {
                break;
            }
        }
    }

    /*
     * ***************************************************************************************************************
     * SCHEDULE MANAGEMENT
     * ***************************************************************************************************************
     */
    /**
     * Assigns an initial Schedule for the given action {@code action}.
     *
     * @param action AllocatableAction.
     */
    public void scheduleAction(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Schedule action " + action + " on resource " + getName());
        // Assign no resource dependencies.
        // The worker will automatically block the tasks when there are not enough resources available.
    }

    /**
     * Unschedules the action assigned to this worker.
     *
     * @param action AllocatableAction to unschedule.
     * @return List of freed actions.
     * @throws ActionNotFoundException When the action is not found.
     */
    public List<AllocatableAction> unscheduleAction(AllocatableAction action) throws ActionNotFoundException {
        LOGGER.debug("[ResourceScheduler] Unschedule action " + action + " on resource scheduler for " + getName()
            + " No new actions have been released.");
        return new LinkedList<>();
    }

    /**
     * Cancels an action execution.
     *
     * @param action AllocatableAction to cancel.
     * @throws ActionNotFoundException When the action is not found.
     */
    public final void cancelAction(AllocatableAction action) throws ActionNotFoundException {
        LOGGER.debug("[ResourceScheduler] Cancel action " + action + " on resource " + getName());
        this.blocked.remove(action);
        unscheduleAction(action);
    }

    /*
     * ***************************************************************************************************************
     * SCORES
     * ***************************************************************************************************************
     */
    /**
     * Returns the score for the action when it is blocked.
     *
     * @param action AllocatableAction.
     * @return Blocked score.
     */
    public Score generateBlockedScore(AllocatableAction action) {
        LOGGER.debug("[ResourceScheduler] Generate blocked score for action " + action);
        return new Score(action.getPriority(), action.getGroupPriority(), 0, 0, 0);
    }

    /**
     * Returns the resource score of action {@code action}.
     *
     * @param action AllocatableAction.
     * @param params Task parameters.
     * @param actionScore Scheduling action score.
     * @return Resource score.
     */
    public Score generateResourceScore(AllocatableAction action, TaskDescription params, Score actionScore) {
        // LOGGER.debug("[ResourceScheduler] Generate resource score for action " + action);

        // Since we are generating the resource score, we copy the previous fields from actionScore
        long priority = actionScore.getPriority();
        long groupId = action.getGroupPriority();

        // Now we compute the rest of the score
        // Computes the resource waiting score
        long waitingScore = -this.blocked.size();
        // Computes the priority of the resource
        long resourceScore = Score.calculateDataLocalityScore(params, this.myWorker);

        return new Score(priority, groupId, resourceScore, waitingScore, 0);
    }

    /**
     * Returns the score of a given implementation {@code impl} for action {@code action} with a fixed resource score
     * {@code resourceScore}.
     *
     * @param action AllocatableAction.
     * @param params Task parameters.
     * @param impl Task implementation to use.
     * @param resourceScore Resource score.
     * @return Implementation score.
     */
    @SuppressWarnings("unchecked")
    public Score generateImplementationScore(AllocatableAction action, TaskDescription params, Implementation impl,
        Score resourceScore) {

        // LOGGER.debug("[ResourceScheduler] Generate implementation score for action " + action);

        // Since we are generating the implementation score, we copy the previous fields from resourceScore
        long priority = resourceScore.getPriority();
        long groupId = action.getGroupPriority();
        long resource = resourceScore.getResourceScore();
        if (!this.myWorker.canRunNow((T) impl.getRequirements())) {
            resource -= Integer.MAX_VALUE;
        }

        // Now we compute the rest of the score
        long waitingScore = resourceScore.getWaitingScore();
        long implScore = -this.getProfile(impl).getAverageExecutionTime();
        return new Score(priority, groupId, resource, waitingScore, implScore);
    }

    /*
     * ***************************************************************************************************************
     * OTHER
     * ***************************************************************************************************************
     */
    /**
     * Clear internal structures.
     */
    public void clear() {
        LOGGER.debug("[ResourceScheduler] Clear resource scheduler " + getName());
        this.running.clear();
        this.blocked.clear();
        this.pendingModifications.clear();
        this.myWorker.releaseAllResources();
    }

    /**
     * Dumps the cores and implementations information into a JSON object.
     * 
     * @return A dump of the cores and implementations information in JSON format.
     */
    public JSONObject toJSONObject() {
        JSONObject jsonObject = new JSONObject();
        Map<String, JSONObject> implsMap = new HashMap<>();

        for (CoreElement ce : CoreManager.getAllCores()) {
            int coreId = ce.getCoreId();
            for (Implementation impl : ce.getImplementations()) {
                int implId = impl.getImplementationId();
                JSONObject implProfile = profiles[coreId][implId].toJSONObject();
                implsMap.put(impl.getSignature(), implProfile);
            }
        }
        jsonObject.put("implementations", implsMap);
        return jsonObject;
    }

    /**
     * Updates the given JSON object with the current information.
     * 
     * @param oldResource JSON object.
     * @return Updated JSON object containing a dump of the cores and implementations.
     */
    public JSONObject updateJSON(JSONObject oldResource) {
        JSONObject difference = new JSONObject();
        JSONObject implsDiff = new JSONObject();
        difference.put("implementations", implsDiff);

        JSONObject implsMap = oldResource.getJSONObject("implementations");

        for (CoreElement ce : CoreManager.getAllCores()) {
            int coreId = ce.getCoreId();
            for (Implementation impl : ce.getImplementations()) {
                int implId = impl.getImplementationId();
                String signature = impl.getSignature();
                if (implsMap.has(signature)) {
                    JSONObject implJSON = implsMap.getJSONObject(signature);
                    JSONObject diff = profiles[coreId][implId].updateJSON(implJSON);
                    implsDiff.put(signature, diff);
                } else {
                    JSONObject implProfile = profiles[coreId][implId].toJSONObject();
                    implsMap.put(signature, implProfile);
                    implsDiff.put(signature, implProfile);
                }
            }
        }

        return difference;
    }

    /**
     * Marks the removed flag in the resource scheduler.
     * 
     * @param b Boolean indicating the removed state.
     */
    public void setRemoved(boolean b) {
        this.removed = true;
    }

    /**
     * Returns whether the RS is removed or not.
     * 
     * @return {@literal true} if the RS is removed, {@literal false} otherwise.
     */
    public boolean isRemoved() {
        return this.removed;
    }

    @Override
    public String toString() {
        try {
            return "ResourceScheduler@" + getName();
        } catch (NullPointerException ne) {
            return super.toString();
        }
    }

}
