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

import es.bsc.compss.COMPSsConstants.Lang;
import es.bsc.compss.agent.loader.Loader;
import es.bsc.compss.agent.types.ApplicationParameter;
import es.bsc.compss.agent.types.RemoteDataInformation;
import es.bsc.compss.agent.types.RemoteDataLocation;
import es.bsc.compss.agent.types.Resource;
import es.bsc.compss.api.impl.COMPSsRuntimeImpl;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.exceptions.ConstructConfigurationException;
import es.bsc.compss.loader.total.ObjectRegistry;
import es.bsc.compss.loader.total.StreamRegistry;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.COMPSsNode;
import es.bsc.compss.types.COMPSsWorker;
import es.bsc.compss.types.CoreElementDefinition;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.annotations.parameter.Direction;
import es.bsc.compss.types.annotations.parameter.OnFailure;
import es.bsc.compss.types.annotations.parameter.StdIOStream;
import es.bsc.compss.types.data.LogicalData;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.implementations.definition.ImplementationDefinition;
import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.resources.Worker;
import es.bsc.compss.types.resources.WorkerResourceDescription;
import es.bsc.compss.types.resources.components.Processor;
import es.bsc.compss.types.resources.configuration.MethodConfiguration;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.ResourceManager;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import storage.StorageException;
import storage.StorageItf;


public class Agent {

    private static final Logger LOGGER = LogManager.getLogger(Loggers.AGENT);
    private static final String LOADER_CLASS_NAME = Loader.class.getCanonicalName();
    private static final String LOADER_METHOD_NAME = "load";
    private static final String LOADER_PARAMS = "(OBJECT_T,OBJECT_T,STRING_T,LONG_T,STRING_T,STRING_T,INT_T,OBJECT_T)";
    private static final String LOADER_SIGNATURE = LOADER_METHOD_NAME + LOADER_PARAMS;

    private static final String AGENT_NAME;
    private static final String LOCAL_ADAPTOR = "es.bsc.compss.adaptors.local.LocalAdaptor";

    private static final COMPSsRuntimeImpl RUNTIME;

    private static final Random APP_ID_GENERATOR = new Random();
    private static final List<AgentInterface<?>> INTERFACES;

    static {
        AGENT_NAME = COMPSsNode.getMasterName();
        LOGGER.info("Initializing agent with name: " + AGENT_NAME);

        String dcConfigPath = System.getProperty(AgentConstants.DATACLAY_CONFIG_PATH);
        LOGGER.debug("DataClay configuration: " + dcConfigPath);
        if (dcConfigPath != null) {
            try {
                StorageItf.init(dcConfigPath);
            } catch (StorageException se) {
                se.printStackTrace(System.err);
                System.err.println("Continuing...");
            }
            Runtime.getRuntime().addShutdownHook(new Thread() {

                public void run() {
                    try {
                        StorageItf.finish();
                    } catch (StorageException se) {
                        se.printStackTrace(System.err);
                        System.err.println("Continuing...");
                    }
                }
            });
        }

        RUNTIME = new COMPSsRuntimeImpl();
        RUNTIME.setObjectRegistry(new ObjectRegistry(RUNTIME));
        RUNTIME.setStreamRegistry(new StreamRegistry(RUNTIME));

        INTERFACES = new LinkedList<>();
    }


    /**
     * Start the runtime within the Agent and sets it up to allow the execution of COMPSs methods.
     */
    public static void start() {

        RUNTIME.startIT();

        CoreElementDefinition ced = new CoreElementDefinition();
        ced.setCeSignature(LOADER_SIGNATURE);
        MethodResourceDescription mrd = new MethodResourceDescription("");
        for (Processor p : mrd.getProcessors()) {
            p.setName("MainProcessor");
        }
        ImplementationDefinition<?> implDef =
            ImplementationDefinition.defineImplementation("METHOD", LOADER_SIGNATURE + LOADER_CLASS_NAME,
                new MethodResourceDescription(""), LOADER_CLASS_NAME, LOADER_METHOD_NAME);
        ced.addImplementation(implDef);
        RUNTIME.registerCoreElement(ced);
    }

    /**
     * Request the execution of a method tasks and detect possible nested tasks.
     *
     * @param lang Programming language of the method.
     * @param ceiClass Core Element interface to detect nested tasks in the code.
     * @param className Name of the class containing the method to execute.
     * @param methodName Name of the method to execute.
     * @param arguments Task arguments.
     * @param target Target object of the task.
     * @param results Results of the task.
     * @param resources Resouces available to run the execution
     * @param monitor Monitor to notify changes on the method execution.
     * @return Identifier of the application associated to the main task
     * @throws AgentException error parsing the CEI
     */
    public static long runMain(Lang lang, String ceiClass, String className, String methodName,
        ApplicationParameter[] arguments, ApplicationParameter target, ApplicationParameter[] results,
        Resource[] resources, AppMonitor monitor) throws AgentException {

        long tasksAppId = Math.abs(APP_ID_GENERATOR.nextLong());
        long mainAppId = Math.abs(APP_ID_GENERATOR.nextLong());
        monitor.setAppId(mainAppId);
        monitor.setTaskAppId(tasksAppId);

        System.out.println("Received execution of " + className + "." + methodName + " on resources ");
        for (Resource r : resources) {
            System.out.println("\t" + r.getName());
            System.out.println("\t\t Adaptor: " + r.getAdaptor());
            System.out.println("\t\t Project: " + r.getProjectConf());
            es.bsc.compss.types.resources.jaxb.ResourcesExternalAdaptorProperties res;
            res = (es.bsc.compss.types.resources.jaxb.ResourcesExternalAdaptorProperties) r.getResourceConf();
            System.out.println("\t\t Resources: " + res);
            System.out.println("\t\t\t Properties:");
            for (es.bsc.compss.types.resources.jaxb.ResourcesPropertyAdaptorType prop : res.getProperty()) {
                System.out.println("\t\t\t\t * " + prop.getName() + " -> " + prop.getValue());
            }

        }

        // Adding Resources
        DynamicMethodWorker masterNode = null;
        try {
            System.out.println("Adding master node ...");
            masterNode = addMasterNode(mainAppId, resources, monitor);
        } catch (Exception e) {
            e.printStackTrace();
            throw new AgentException("Error creating master.", e);
        }

        if (masterNode == null) {
            System.out.println("Must forward request to one of the nodes");
        } else {
            System.out.println("Adding worker nodes ...");
            try {
                addWorkerNodes(tasksAppId, resources, monitor);
            } catch (Exception e) {
                throw new AgentException("Error creating worker.", e);
            }
            try {
                int taskParamsCount = arguments.length;
                if (target != null) {
                    taskParamsCount++;
                }
                taskParamsCount += results.length;
                int loadParamsCount = 7;
                int totalParamsCount = taskParamsCount + loadParamsCount;
                Object[] params = new Object[6 * totalParamsCount];

                Object[] loadParams = new Object[] { RUNTIME,
                    DataType.OBJECT_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "runtime", // Runtime API
                    RUNTIME,
                    DataType.OBJECT_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "api", // Loader API
                    ceiClass,
                    DataType.STRING_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "ceiClass", // CEI
                    tasksAppId,
                    DataType.LONG_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "appId", // Nested tasks App ID
                    className,
                    DataType.STRING_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "className", // Class name
                    methodName,
                    DataType.STRING_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "methodName", // Method name
                    /*
                     * When passing a single parameter with array type to the loaded method, the Object... parameter of
                     * the load method assumes that each element of the array is a different parameter ( any array
                     * matches Object...). To avoid it, we add a phantom basic-type parameter that avoids any data
                     * transfer and ensures that the array is detected as the second parameter -- Object... is resolved
                     * as [ Integer, array].
                     */
                    3,
                    DataType.INT_T,
                    Direction.IN,
                    StdIOStream.UNSPECIFIED,
                    "",
                    "fakeParam", // Fake param
                };

                System.arraycopy(loadParams, 0, params, 0, loadParams.length);
                int position = loadParams.length;
                for (ApplicationParameter param : arguments) {
                    LOGGER.debug("\t Parameter:" + param.getParamName());
                    addParameterToTaskArguments(param, position, params);
                    position += 6;
                }

                if (target != null) {
                    LOGGER.debug("\t Target:" + target.getParamName());
                    addParameterToTaskArguments(target, position, params);
                    position += 6;
                }

                for (ApplicationParameter param : results) {
                    params[position] = new Object();
                    params[position + 1] = param.getType();
                    params[position + 2] = param.getDirection();
                    params[position + 3] = param.getStdIOStream();
                    params[position + 4] = param.getPrefix();
                    params[position + 5] = param.getParamName();
                    position += 6;
                }

                RUNTIME.executeTask(mainAppId, // Task application ID
                    monitor, // Corresponding task monitor
                    lang, // language of the task
                    true, LOADER_CLASS_NAME, LOADER_METHOD_NAME, LOADER_SIGNATURE + LOADER_CLASS_NAME, // Method to run
                    OnFailure.RETRY, // On failure behavior
                    0, // Time out of the task
                    false, 1, false, false, // Scheduler hints
                    false, null, totalParamsCount, // Parameters information
                    params // Argument values
                );
            } catch (Exception e) {
                throw new AgentException(e);
            }
        }
        return mainAppId;
    }

    /**
     * Requests the execution of a method as a task.
     *
     * @param lang programming language of the method
     * @param className name of the class containing the method to execute
     * @param methodName name of the method to execute
     * @param arguments paramter description of the task's arguments
     * @param target paramter description of the task's callee
     * @param results paramter description of the task's results
     * @param resources resources available to run the task
     * @param requirements requirements to run the task
     * @param monitor monitor to notify changes on the method execution
     * @return Identifier of the application associated to the task
     * @throws AgentException could not retrieve the value of some parameter
     */
    public static long runTask(Lang lang, String className, String methodName, ApplicationParameter[] arguments,
        ApplicationParameter target, ApplicationParameter[] results, MethodResourceDescription requirements,
        Resource[] resources, AppMonitor monitor) throws AgentException {
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("New request to run as a " + lang + " task " + className + "." + methodName);
        System.out.println("Parameters: ");
        for (ApplicationParameter param : arguments) {
            System.out.println("\t* " + param);
        }

        LOGGER.debug("New request to run as a " + lang + " task " + className + "." + methodName);
        LOGGER.debug("Parameters: ");
        for (ApplicationParameter param : arguments) {
            LOGGER.debug("\t* " + param);
        }
        LOGGER.debug("The task requires " + requirements);
        long appId = Math.abs(APP_ID_GENERATOR.nextLong());

        monitor.setAppId(appId);
        try {
            System.out.println("Running Task " + appId);
            try {
                addWorkerNodes(appId, resources, monitor);
            } catch (Exception e) {
                throw new AgentException("Error creating worker.", e);
            }
            // PREPARING PARAMETERS
            StringBuilder typesSB = new StringBuilder();

            int paramsCount = arguments.length;
            if (target != null) {
                paramsCount++;
            }
            paramsCount += results.length;

            Object[] params = new Object[6 * paramsCount];
            int position = 0;
            LOGGER.debug("Handles parameters:");
            for (ApplicationParameter param : arguments) {
                LOGGER.debug("\t Parameter:" + param.getParamName());
                if (typesSB.length() > 0) {
                    typesSB.append(",");
                }
                if (param.getType() != DataType.PSCO_T) {
                    typesSB.append(param.getType().toString());
                } else {
                    typesSB.append("OBJECT_T");
                }
                addParameterToTaskArguments(param, position, params);
                position += 6;
            }

            if (target != null) {
                LOGGER.debug("\t Target:" + target.getParamName());
                addParameterToTaskArguments(target, position, params);
                position += 6;
            }

            for (ApplicationParameter param : results) {
                params[position] = new Object();
                params[position + 1] = param.getType();
                params[position + 2] = param.getDirection();
                params[position + 3] = param.getStdIOStream();
                params[position + 4] = param.getPrefix();
                params[position + 5] = param.getParamName();
                position += 6;
            }

            String paramsTypes = typesSB.toString();

            String ceSignature = methodName + "(" + paramsTypes + ")";
            String implSignature = methodName + "(" + paramsTypes + ")" + className;
            CoreElementDefinition ced = new CoreElementDefinition();
            ced.setCeSignature(ceSignature);
            ImplementationDefinition<?> implDef = ImplementationDefinition.defineImplementation("METHOD", implSignature,
                requirements, className, methodName);
            ced.addImplementation(implDef);
            RUNTIME.registerCoreElement(ced);

            RUNTIME.executeTask(appId, // APP ID
                monitor, // Corresponding task monitor
                lang, className, methodName, // Method to call
                false, 1, false, false, // Scheduling information
                target != null, paramsCount, // Parameter information
                OnFailure.RETRY, // On failure behavior
                0, // Time out of the task
                params // Parameter values
            );

        } catch (Exception e) {
            throw new AgentException(e);
        }
        return appId;
    }

    private static void addParameterToTaskArguments(ApplicationParameter param, int position, Object[] arguments)
        throws AgentException, Exception {

        RemoteDataInformation remote = param.getRemoteData();
        if (param.getRemoteData() == null) {
            LOGGER.debug("\t\tUsing value passed in as parameter");
            arguments[position] = param.getValueContent();
        } else {
            Object stub = new Object();
            LOGGER.debug("\t\tRegistering manually " + stub + "as" + param.getRemoteData());
            arguments[position] = stub;
            Agent.addRemoteData(remote);
            RUNTIME.registerData(param.getType(), stub, remote.getRenaming());
        }
        System.out.println("Loading argument " + arguments[position]);
        arguments[position + 1] = param.getType();
        arguments[position + 2] = param.getDirection();
        arguments[position + 3] = param.getStdIOStream();
        arguments[position + 4] = param.getPrefix();
        arguments[position + 5] = param.getParamName();
    }

    private static void addRemoteData(RemoteDataInformation remote) throws AgentException {
        System.out.println("ADDING REMOTE DATA " + remote);
        int addedSources = 0;
        LogicalData ld = Comm.getData(remote.getRenaming());
        System.out.println(ld);
        LogicalData otherNamedLocalData = null;

        LinkedList<DataLocation> locations = new LinkedList<>();
        for (RemoteDataLocation loc : remote.getSources()) {
            try {
                String path = loc.getPath();
                SimpleURI uri = new SimpleURI(path);
                Resource<?, ?> r = loc.getResource();
                String workerName = r.getName();
                Worker<? extends WorkerResourceDescription> host = ResourceManager.getWorker(workerName);
                if (host == null) {
                    MethodResourceDescription mrd = r.getDescription();
                    String adaptor = r.getAdaptor();
                    Map<String, Object> projectConf = new HashMap<>();
                    projectConf.put("Properties", r.getProjectConf());
                    Map<String, Object> resourcesConf = new HashMap<>();
                    resourcesConf.put("Properties", r.getResourceConf());
                    host = registerWorker(null, workerName, mrd, adaptor, projectConf, resourcesConf);
                } else {
                    if (host == Comm.getAppHost()) {
                        LogicalData localData = Comm.getData(uri.getPath());
                        if (localData != null) {
                            otherNamedLocalData = localData;
                            addedSources++;
                            continue;
                        }
                    }
                }
                DataLocation dl = DataLocation.createLocation(host, uri);
                locations.add(dl);
            } catch (AgentException | IOException e) {
                // Do nothing. Ignore location
                e.printStackTrace();
            }
        }

        if (ld == null) {
            if (otherNamedLocalData == null) {
                ld = Comm.registerData(remote.getRenaming());
            } else {
                Comm.linkData(remote.getRenaming(), otherNamedLocalData.getName());
                addedSources++;
            }
        }
        for (DataLocation loc : locations) {
            ld.addLocation(loc);
            addedSources++;
        }
        System.out.println(ld);
        if (addedSources == 0) {
            throw new AgentException("Could not add any source for data " + remote.getRenaming());
        }
    }

    /**
     * Adds new resources into the resource pool.
     *
     * @param r Description of the resources to add into the resource pool
     * @param appId ApplicationId of the tasks that can use such resources
     * @throws AgentException could not create a configuration to start using this resource
     */
    public static void addResources(Resource r, Long appId) throws AgentException {
        Long resourceAppId = null;
        AppMonitor monitor = null;
        if (appId != null) {
            monitor = AppMonitor.getMonitorForApp(appId);
            if (monitor == null) {
                throw new AgentException("Unknown application " + appId);
            }
            resourceAppId = monitor.getTaskAppId();
            if (resourceAppId == null) {
                resourceAppId = appId;
            }
        }
        addResources(r, resourceAppId, monitor);
    }

    private static DynamicMethodWorker addResources(Resource r, Long appId, AppMonitor monitor) throws AgentException {
        System.out.println("Adding Resource " + r.getName() + " for application " + appId);
        String workerName = r.getName();
        MethodResourceDescription description = r.getDescription();

        DynamicMethodWorker worker = ResourceManager.getDynamicResource(workerName + "_" + appId);
        if (worker != null) {
            ResourceManager.increasedDynamicWorker(worker, description);
        } else {
            String adaptor = r.getAdaptor();
            Map<String, Object> projectConf = new HashMap<>();
            projectConf.put("Properties", r.getProjectConf());
            Map<String, Object> resourcesConf = new HashMap<>();
            resourcesConf.put("Properties", r.getResourceConf());
            worker = registerWorker(appId, workerName, description, adaptor, projectConf, resourcesConf);
        }

        if (monitor != null) {
            monitor.addNode(worker);
        }
        return worker;
    }

    private static DynamicMethodWorker registerWorker(Long appId, String workerName,
        MethodResourceDescription description, String adaptor, Map<String, Object> projectConf,
        Map<String, Object> resourcesConf) throws AgentException {

        System.out.println("REGISTERING NEW WORKER with adaptor " + adaptor);
        if (description == null) {
            description = new MethodResourceDescription();
        }

        MethodConfiguration mc;
        try {
            mc = (MethodConfiguration) Comm.constructConfiguration(adaptor, projectConf, resourcesConf);
        } catch (ConstructConfigurationException e) {
            throw new AgentException(e.getMessage(), e);
        }
        int limitOfTasks = mc.getLimitOfTasks();
        int computingUnits = description.getTotalCPUComputingUnits();
        if (limitOfTasks < 0 && computingUnits < 0) {
            mc.setLimitOfTasks(0);
            mc.setTotalComputingUnits(0);
        } else {
            mc.setLimitOfTasks(Math.max(limitOfTasks, computingUnits));
            mc.setTotalComputingUnits(Math.max(limitOfTasks, computingUnits));
        }
        mc.setLimitOfGPUTasks(description.getTotalGPUComputingUnits());
        mc.setTotalGPUComputingUnits(description.getTotalGPUComputingUnits());
        mc.setLimitOfFPGATasks(description.getTotalFPGAComputingUnits());
        mc.setTotalFPGAComputingUnits(description.getTotalFPGAComputingUnits());
        mc.setLimitOfOTHERsTasks(description.getTotalOTHERComputingUnits());
        mc.setTotalOTHERComputingUnits(description.getTotalOTHERComputingUnits());

        mc.setHost(workerName);
        DynamicMethodWorker worker;
        worker = new DynamicMethodWorker(workerName + "_" + appId, description, mc, new HashMap<>());
        ResourceManager.addDynamicWorker(worker, description, appId);
        return worker;
    }

    private static DynamicMethodWorker addMasterNode(long mainAppId, Resource[] resources, AppMonitor monitor)
        throws AgentException {
        DynamicMethodWorker mw = null;
        for (Resource r : resources) {
            System.out.println("\t Checking Resource " + r.getName());
            if (isMaster(r)) {
                System.out.println("\t\t Is the local node.");
                MethodResourceDescription mrd = r.getDescription();
                List<Processor> processors = mrd.getProcessors();
                Iterator<Processor> procsItr = processors.iterator();
                while (procsItr.hasNext()) {
                    Processor p = procsItr.next();
                    if (p.isCPU()) {
                        // Remove one CU from the Local resource
                        int finalCUCount = p.getComputingUnits() - 1;
                        if (finalCUCount == 0) {
                            procsItr.remove();
                        } else {
                            p.setComputingUnits(p.getComputingUnits() - 1);
                        }
                        mrd.setProcessors(processors);

                        // Create a new Worker for the main app
                        Processor p2 = new Processor(p);
                        p2.setComputingUnits(1);
                        MethodResourceDescription masterDescription = new MethodResourceDescription();
                        masterDescription.addProcessor(p2);

                        Resource master = new Resource(r.getName(), masterDescription, LOCAL_ADAPTOR, null, null);
                        mw = addLocalNode(master, mainAppId, monitor);
                        break;
                    }
                }
            } else {
                System.out.println("\t\t Is not the local node");
            }
        }
        return mw;
    }

    private static void addWorkerNodes(long appId, Resource[] resources, AppMonitor monitor) throws AgentException {
        for (Resource r : resources) {
            System.out.println("\tAdding " + r.getName());
            // Update processorCounts
            MethodResourceDescription desc = r.getDescription();
            desc.setProcessors(desc.getProcessors());
            if (r.getDescription().getTotalCPUComputingUnits() > 0) {
                DynamicMethodWorker mw;
                if (isMaster(r)) {
                    mw = addLocalNode(r, appId, monitor);
                } else {
                    mw = addResources(r, appId, monitor);
                }
            }
        }
    }

    private static DynamicMethodWorker addLocalNode(Resource r, Long appId, AppMonitor monitor) {
        System.out.println("\t\t\t Adding LocalNode");
        String workerName = r.getName();
        MethodResourceDescription description = r.getDescription();

        int limitCPUTasks = description.getTotalCPUComputingUnits();
        int limitGPUTasks = description.getTotalGPUComputingUnits();
        int limitFPGATasks = description.getTotalFPGAComputingUnits();
        int limitOtherTasks = description.getTotalOTHERComputingUnits();
        COMPSsWorker worker = (COMPSsWorker) Comm.getAppHost().getNode();
        DynamicMethodWorker mw = new DynamicMethodWorker(workerName + "_" + appId, description, worker, limitCPUTasks,
            limitGPUTasks, limitFPGATasks, limitOtherTasks, new HashMap<>());
        worker.increaseComputingCapabilities(description);
        ResourceManager.addDynamicWorker(mw, description, appId);
        monitor.addNode(mw);
        return mw;
    }

    /**
     * Requests the agent to stop using some resources from a node.
     *
     * @param workerName name of the worker to whom the resources belong.
     * @param reduction description of the resources to stop using.
     * @param appId applicationId associated to the resource
     * @throws AgentException the worker was not set up for the agent.
     */
    public static void removeResources(String workerName, MethodResourceDescription reduction, Long appId)
        throws AgentException {
        System.out.println("Removing Resource " + workerName + " for application " + appId);
        Long resourceAppId = null;
        AppMonitor monitor = null;
        if (appId != null) {
            monitor = AppMonitor.getMonitorForApp(appId);
            if (monitor == null) {
                throw new AgentException("Unknown application " + appId);
            }
            resourceAppId = monitor.getTaskAppId();
            if (resourceAppId == null) {
                resourceAppId = appId;
            }
        }

        DynamicMethodWorker worker = ResourceManager.getDynamicResource(workerName + "_" + resourceAppId);
        if (worker != null) {
            ResourceManager.requestWorkerReduction(worker, reduction);
        } else {
            throw new AgentException("Resource " + workerName + " was not set up for this agent. Ignoring request.");
        }
    }

    /**
     * Request the agent to stop using all the resources from a node.
     *
     * @param workerName name of the worker to stop using
     * @param appId applicationId associated to the resource
     * @throws AgentException the worker was not set up for the agent.
     */
    public static void removeNode(String workerName, Long appId) throws AgentException {
        System.out.println("Removing Node " + workerName + " for application " + appId);
        Long resourceAppId = null;
        AppMonitor monitor = null;
        if (appId != null) {
            monitor = AppMonitor.getMonitorForApp(appId);
            if (monitor == null) {
                throw new AgentException("Unknown application " + appId);
            }
            resourceAppId = monitor.getTaskAppId();
            if (resourceAppId == null) {
                resourceAppId = appId;
            }
        }

        try {
            ResourceManager.requestWholeWorkerReduction(workerName + "_" + resourceAppId);
        } catch (NullPointerException e) {
            throw new AgentException("Resource " + workerName + " was not set up for this agent. Ignoring request.");
        }
    }

    /**
     * Forces the agent to remove a node with which it has lost the connection.
     *
     * @param workerName name of the worker to stop using
     * @param appId applicationId associated to the resource
     * @throws AgentException the worker was not set up for the agent.
     */
    public static void lostNode(String workerName, Long appId) throws AgentException {
        System.out.println("Removing Node " + workerName + " for application " + appId);
        Long resourceAppId = null;
        AppMonitor monitor = null;
        if (appId != null) {
            monitor = AppMonitor.getMonitorForApp(appId);
            if (monitor == null) {
                throw new AgentException("Unknown application " + appId);
            }
            resourceAppId = monitor.getTaskAppId();
            if (resourceAppId == null) {
                resourceAppId = appId;
            }
        }

        try {
            ResourceManager.notifyWholeWorkerReduction(workerName + "_" + resourceAppId);
        } catch (NullPointerException e) {
            throw new AgentException("Resource " + workerName + " was not set up for this agent. Ignoring request.");
        }
    }

    /**
     * Starts an agent interface.
     *
     * @param conf Agent Interface configuration parameters
     * @throws ClassNotFoundException Could not find the specify agent interface class
     * @throws InstantiationException Could not instantiate the agent interface
     * @throws IllegalAccessException Could not call the empty constructor because is private
     * @throws AgentException Error during the interface boot process
     */
    @SuppressWarnings("unchecked")
    public static final void startInterface(AgentInterfaceConfig conf)
        throws ClassNotFoundException, InstantiationException, IllegalAccessException, AgentException {

        AgentInterface<AgentInterfaceConfig> itf = (AgentInterface<AgentInterfaceConfig>) conf.getAgentInterface();
        itf.start(conf);
        INTERFACES.add(itf);
    }

    private static AgentInterfaceConfig getInterfaceConfig(String className, JSONObject arguments)
        throws ClassNotFoundException, InstantiationException, IllegalAccessException, AgentException {

        Class<?> agentClass = Class.forName(className);
        AgentInterface<?> itf = (AgentInterface<?>) agentClass.newInstance();
        return itf.configure(arguments);
    }

    /**
     * Returns the name of the Agent.
     *
     * @return name of the agent.
     */
    public static String getName() {
        return AGENT_NAME;
    }

    /**
     * Main method to start a COMPSs agent. (Currently it only allows a REST agent)
     *
     * @param args Set of JSONObjects describing the AgentInterfaces to start and their configurations.
     * @throws Exception Could not create the configuration for the REST agent due to internal errors
     */
    public static final void main(String[] args) throws Exception {
        LinkedList<AgentInterfaceConfig> agents = new LinkedList<>();

        String agentConfig = System.getProperty(AgentConstants.AGENT_CONFIG_PATH);

        if (agentConfig != null && !agentConfig.isEmpty()) {
            LOGGER.info("Reading Agent config from " + agentConfig);
            File configFile = new File(agentConfig);
            if (configFile.exists()) {
                String configString = new String(Files.readAllBytes(configFile.toPath()));
                JSONArray array = new JSONArray(configString);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject jo = array.getJSONObject(i);
                    try {
                        String interfaceClass = jo.getString("AGENT_IMPL");
                        JSONObject conf = jo.getJSONObject("CONF");
                        LOGGER.info("Loading " + interfaceClass + "'s agent interface");
                        AgentInterfaceConfig aic = getInterfaceConfig(interfaceClass, conf);
                        agents.add(aic);
                    } catch (Exception e) {
                        ErrorManager.warn("Unexpected format for agent config: " + jo);
                    }
                }
            } else {
                ErrorManager.warn("Could not find the agent configuration file " + agentConfig);
            }
        }

        for (String arg : args) {
            try {
                JSONObject jo = new JSONObject(arg);
                String interfaceClass = jo.getString("AGENT_IMPL");
                JSONObject conf = jo.getJSONObject("CONF");
                LOGGER.info("Loading " + agentConfig + "'s agent interface");
                AgentInterfaceConfig aic = getInterfaceConfig(interfaceClass, conf);
                agents.add(aic);
            } catch (Exception e) {
                ErrorManager.warn("Unexpected format for agent config: " + arg);
            }
        }

        for (AgentInterfaceConfig agent : agents) {
            try {
                startInterface(agent);
            } catch (Exception e) {
                ErrorManager.warn("Could not start Agent", e);
            }
        }
        if (INTERFACES.isEmpty()) {
            ErrorManager.fatal("Could not start any interface");
        }
        start();
    }

    private static boolean isMaster(Resource r) {
        String name = r.getName();
        String hostName = name;
        try {
            if (!hostName.contains("://")) {
                hostName = "http://" + hostName;
            }
            URI u = new URI(hostName);
            hostName = u.getHost();
        } catch (URISyntaxException ex) {
            hostName = name;
        }

        return (name.equals(AGENT_NAME) || hostName.equals(AGENT_NAME) || name.equals("localhost"));
    }

}
