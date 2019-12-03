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

import es.bsc.compss.COMPSsConstants.Lang;
import es.bsc.compss.agent.Agent;
import es.bsc.compss.agent.AgentException;
import es.bsc.compss.agent.AgentInterface;
import es.bsc.compss.agent.RESTAgentConstants;
import es.bsc.compss.agent.rest.types.ApplicationParameterImpl;
import es.bsc.compss.agent.rest.types.Orchestrator;
import es.bsc.compss.agent.rest.types.messages.EndApplicationNotification;
import es.bsc.compss.agent.rest.types.messages.IncreaseNodeNotification;
import es.bsc.compss.agent.rest.types.messages.LostNodeNotification;
import es.bsc.compss.agent.rest.types.messages.ReduceNodeRequest;
import es.bsc.compss.agent.rest.types.messages.RemoveNodeRequest;
import es.bsc.compss.agent.rest.types.messages.StartApplicationRequest;
import es.bsc.compss.agent.types.ApplicationParameter;
import es.bsc.compss.agent.types.Resource;
import es.bsc.compss.agent.util.RemoteJobsRegistry;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.annotations.parameter.DataType;
import es.bsc.compss.types.annotations.parameter.Direction;
import es.bsc.compss.types.annotations.parameter.StdIOStream;
import es.bsc.compss.types.job.JobEndStatus;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.resources.components.Processor;
import es.bsc.compss.util.EnvironmentLoader;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.ResourceManager;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.eclipse.jetty.server.Server;
import org.json.JSONObject;


/**
 * Class providing a REST Interface for the COMPSs Agent.
 */
@Path("/COMPSs")
public class RESTAgent implements AgentInterface<RESTAgentConf> {

    // Logger
    private static final Logger LOGGER = LogManager.getLogger(Loggers.AGENT);

    private int port;
    private Server server = null;


    @Override
    public RESTAgentConf configure(final JSONObject confJSON) throws AgentException {
        RESTAgentConf conf;
        try {
            String portSTR = confJSON.getString("PORT");
            portSTR = EnvironmentLoader.loadFromEnvironment(portSTR);
            int port = Integer.valueOf(portSTR);
            if (port > 0) {
                conf = new RESTAgentConf(this, port);
            } else {
                throw new AgentException("Invalid port number for REST agent's interface.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new AgentException(e);
        }
        return conf;
    }

    @Override
    public synchronized void start(RESTAgentConf args) throws AgentException {
        if (this.server != null) {
            // Server already started. Ignore start;
            return;
        }
        RESTServiceLauncher launcher = null;
        try {
            this.port = args.getPort();
            RESTAgentConstants.COMPSS_AGENT_PORT = port;
            launcher = new RESTServiceLauncher(port);
            LOGGER.info("Starting RESTAgent on port " + port);
            new Thread(launcher).start();
            launcher.waitForBoot();
        } catch (Exception e) {
            throw new AgentException(e);
        }
        if (launcher.getStartError() != null) {
            throw new AgentException(launcher.getStartError());
        } else {
            this.server = launcher.getServer();
        }
    }

    @Override
    public synchronized void stop() {
        if (this.server != null) {
            try {
                this.server.stop();
            } catch (Exception ex) {
                ErrorManager.warn("Could not stop the REST server for the Agent at port " + port, ex);
            } finally {
                server.destroy();
                server = null;
            }
        }
    }

    @GET
    @Path("test/")
    public Response test() {
        System.out.println("test invoked");
        return Response.ok().build();
    }

    /**
     * Prints through the agent's standard output stream the resources currently available.
     *
     * @return REST response confirming the execution of the print command
     */
    @GET
    @Path("printResources/")
    public Response getResources() {
        System.out.println(ResourceManager.getCurrentState(""));
        return Response.ok().build();
    }

    /**
     ** Adds new available resources to the runtime system.
     *
     * @param nodeRequest requested resource description.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("addResources/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response addResource(IncreaseNodeNotification nodeRequest) {
        Resource<?, ?> r = nodeRequest.getResource();
        // Updating processors
        MethodResourceDescription description = r.getDescription();
        List<Processor> procs = description.getProcessors();
        description.setProcessors(procs);

        Long appId = nodeRequest.getAppId();
        try {
            Agent.addResources(r, appId);
        } catch (AgentException ex) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ex.getMessage()).build();
        }
        return Response.ok().build();
    }

    /**
     * Removes some resources from the pool of available resources.
     *
     * @param request request describing the resources to release.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("removeResources/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response removeResources(ReduceNodeRequest request) {
        String name = request.getWorkerName();
        MethodResourceDescription mrd = request.getResources();
        Long appId = request.getAppId();
        List<Processor> procs = mrd.getProcessors();
        mrd.setProcessors(procs);
        try {
            Agent.removeResources(name, mrd, appId);
        } catch (AgentException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();

        }
        return Response.ok().build();
    }

    /**
     * Removes all the resources from a node from the resource pool.
     *
     * @param request request describing the node to release.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("removeNode/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response removeResource(RemoveNodeRequest request) {
        String name = request.getWorkerName();
        Long appId = request.getAppId();
        try {
            Agent.removeNode(name, appId);
        } catch (AgentException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();

        }
        return Response.ok().build();
    }

    /**
     * Removes all the resources from the node and assumes that all the tasks running there fail.
     *
     * @param notification request describing the lost node.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("lostNode/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response lostResource(LostNodeNotification notification) {
        String name = notification.getWorkerName();
        Long appId = notification.getAppId();
        try {
            Agent.lostNode(name, appId);
        } catch (AgentException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();

        }
        return Response.ok().build();
    }

    /**
     * Request to run a method as a task.
     *
     * @param request description of the method to execute.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("startApplication/")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response startApplication(StartApplicationRequest request) {
        System.out.println("RESOURCES ON TASK RECEPTION");
        for (es.bsc.compss.types.resources.Worker w : ResourceManager.getAllWorkers()) {
            System.out.println(w.getName() + " " + w.getDescription());
        }
        Response response;
        String ceiClass = request.getCeiClass();
        if (ceiClass != null) {
            response = runMain(request);
        } else {
            response = runTask(request);
        }
        return response;
    }

    private static Response runMain(StartApplicationRequest request) {
        String serviceInstanceId = request.getServiceInstanceId();
        String ceiClass = request.getCeiClass();

        String className = request.getClassName();
        String methodName = request.getMethodName();
        ApplicationParameter[] params = request.getParams();
        /*
         * Object[] params; try { params = request.getParamsValuesContent(); } catch (Exception cnfe) { return
         * Response.status(Response.Status.INTERNAL_SERVER_ERROR) .entity("Could not recover an input parameter value. "
         * + cnfe.getLocalizedMessage()).build(); }
         */
        String computeNodeID = Agent.getName();
        String operationName = className + "." + methodName;
        AppMainMonitor monitor = new AppMainMonitor(serviceInstanceId, computeNodeID, operationName);
        Resource[] resources = request.getResources();
        long appId;
        try {
            appId = Agent.runMain(Lang.JAVA, ceiClass, className, methodName, params, null, new ApplicationParameter[0],
                resources, monitor);
        } catch (AgentException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
        monitor.start("" + appId);
        return Response.ok(appId, MediaType.TEXT_PLAIN).build();
    }

    private static Response runTask(StartApplicationRequest request) {
        String className = request.getClassName();
        String methodName = request.getMethodName();
        ApplicationParameterImpl[] arguments = request.getParams();
        ApplicationParameterImpl target = request.getTarget();
        ApplicationParameterImpl[] results;
        Resource[] resources = request.getResources();
        boolean hasResult = request.isHasResult();
        if (hasResult) {
            results = new ApplicationParameterImpl[1];
            results[0] = new ApplicationParameterImpl(new Object(), Direction.OUT, DataType.OBJECT_T,
                StdIOStream.UNSPECIFIED, "", "result");
        } else {
            results = new ApplicationParameterImpl[0];
        }
        long appId;
        Orchestrator orchestrator = request.getOrchestrator();
        int numParams = arguments.length;
        if (target != null) {
            numParams++;
        }
        if (hasResult) {
            numParams++;
        }
        AppTaskMonitor monitor = new AppTaskMonitor(numParams, orchestrator);

        try {
            appId = Agent.runTask(Lang.JAVA, className, methodName, arguments, target, results,
                MethodResourceDescription.EMPTY_FOR_CONSTRAINTS, resources, monitor);
        } catch (AgentException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
        return Response.ok(appId, MediaType.TEXT_PLAIN).build();
    }

    /**
     * Notification that a task submitted to another REST agent has finished.
     *
     * @param notification Result of the operation.
     * @return REST response containing the service reply to the request
     */
    @PUT
    @Path("endApplication/")
    @Consumes(MediaType.APPLICATION_XML)
    public Response endApplication(EndApplicationNotification notification) {
        String jobId = notification.getJobId();
        JobEndStatus endStatus = notification.getEndStatus();
        DataType[] resultTypes = notification.getParamTypes();
        String[] resultLocations = notification.getParamLocations();
        RemoteJobsRegistry.notifyJobEnd(jobId, endStatus, resultTypes, resultLocations);
        return Response.ok().build();
    }

    /**
     * Main method of the application starting a REST service.
     *
     * @param args Arguments to configuring the REST service. Position 0: port.
     * @throws Exception Error starting the REST Agent
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        RESTAgent ra = new RESTAgent();
        RESTAgentConf config = new RESTAgentConf(ra, port);
        Agent.startInterface(config);
        Agent.start();
    }

}
