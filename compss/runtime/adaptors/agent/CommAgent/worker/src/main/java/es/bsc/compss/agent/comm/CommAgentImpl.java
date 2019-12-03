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

import es.bsc.comm.nio.NIONode;
import es.bsc.compss.COMPSsConstants;
import es.bsc.compss.COMPSsConstants.Lang;
import es.bsc.compss.agent.Agent;
import es.bsc.compss.agent.AgentException;
import es.bsc.compss.agent.AgentInterface;
import es.bsc.compss.agent.AppMonitor;
import es.bsc.compss.agent.comm.messages.types.CommParam;
import es.bsc.compss.agent.comm.messages.types.CommResource;
import es.bsc.compss.agent.comm.messages.types.CommTask;
import es.bsc.compss.agent.types.ApplicationParameter;
import es.bsc.compss.agent.types.Resource;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.nio.NIOParam;
import es.bsc.compss.types.implementations.MethodImplementation;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.util.EnvironmentLoader;
import es.bsc.compss.util.ErrorManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;


public class CommAgentImpl implements AgentInterface<CommAgentConfig>, CommAgent {

    // Logger
    private static final Logger LOGGER = LogManager.getLogger(Loggers.AGENT);

    // Adaptor
    private CommAgentAdaptor adaptor;


    public CommAgentImpl() {
        this.adaptor = null;
    }

    /*
     * ----------- Agent Interface Methods ---------
     */
    @Override
    public CommAgentConfig configure(final JSONObject confJSON) throws AgentException {
        CommAgentConfig conf;
        try {
            String portSTR = confJSON.getString("PORT");
            portSTR = EnvironmentLoader.loadFromEnvironment(portSTR);
            int port = Integer.valueOf(portSTR);
            if (port > 0) {
                conf = new CommAgentConfig(this, port);
            } else {
                throw new AgentException("Invalid port number for Comm agent's interface.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new AgentException(e);
        }
        return conf;
    }

    @Override
    public void start(final CommAgentConfig conf) throws AgentException {
        final int port = conf.getPort();
        System.setProperty(COMPSsConstants.MASTER_PORT, Integer.toString(port));
        CommAgentAdaptor nioAdaptor = (CommAgentAdaptor) Comm.getAdaptors().get(CommAgentAdaptor.ID);
        CommAgentAdaptor commAgentAdaptor;
        commAgentAdaptor = (CommAgentAdaptor) Comm.getAdaptors().get(CommAgentAdaptor.class.getCanonicalName());
        if (nioAdaptor == null && commAgentAdaptor == null) {
            adaptor = new CommAgentAdaptor(this);
            LOGGER.info("Starting CommAgent on port " + port);
            adaptor.init();
            Comm.registerAdaptor(CommAgentAdaptor.ID, adaptor);
            Comm.registerAdaptor(CommAgentAdaptor.class.getCanonicalName(), adaptor);
        } else {
            if (nioAdaptor == null) {
                adaptor = commAgentAdaptor;
                Comm.registerAdaptor(CommAgentAdaptor.class.getCanonicalName(), adaptor);
            } else {
                // commAgentAdaptor == null
                adaptor = nioAdaptor;
                Comm.registerAdaptor(CommAgentAdaptor.ID, adaptor);
            }
        }
    }

    @Override
    public void stop() {
        // Nothing to do
    }

    /*
     * ------------- Comm Agent Methods ------------
     */
    @Override
    public void print(Object o) {
        System.out.println(o);
    }

    @Override
    public void addResources(Resource<?, ?> res) {
        try {
            Agent.addResources(res, null);
        } catch (AgentException ex) {
            ErrorManager.warn("Could not add the new server", ex);
        }
    }

    @Override
    public void removeResources(Resource<?, ?> resource) {
        try {
            Agent.removeResources(resource.getName(), resource.getDescription(), null);
        } catch (AgentException ae) {
            ae.printStackTrace();
        }
    }

    @Override
    public void removeNode(String node) {
        try {
            Agent.removeNode(node, null);
        } catch (AgentException ae) {
            ae.printStackTrace();
        }
    }

    @Override
    public void lostNode(String node) {
        try {
            Agent.lostNode(node, null);
        } catch (AgentException ae) {
            ae.printStackTrace();
        }
    }

    @Override
    public void receivedNewTask(NIONode master, CommTask request) {
        Lang lang = request.getLang();
        MethodImplementation impl = (MethodImplementation) request.getMethodImplementation();
        String className = impl.getDeclaringClass();
        String methodName = impl.getAlternativeMethodName();

        int argsCount = request.getParams().size();
        int resultsCount = request.getResults().size();

        ApplicationParameter[] arguments = new ApplicationParameter[argsCount];
        int paramId = 0;
        for (NIOParam np : request.getParams()) {
            arguments[paramId] = (CommParam) np;
            paramId++;
        }
        ApplicationParameter target = (CommParam) request.getTarget();
        ApplicationParameter[] results = new ApplicationParameter[resultsCount];
        paramId = 0;
        for (NIOParam np : request.getResults()) {
            results[paramId] = (CommParam) np;
            paramId++;
        }

        String cei = request.getCei();
        MethodResourceDescription requirements = request.getRequirements();
        CommResource orchestrator = request.getOrchestrator();
        System.out.println("S'ha de notificat la peticio a " + orchestrator);
        AppMonitor monitor;
        if (orchestrator == null) {
            monitor = new PrintMonitor();
        } else {
            monitor = new TaskMonitor(orchestrator, request);
        }

        if (cei != null) {
            startMain(lang, className, methodName, arguments, target, results, cei, requirements, monitor);
        } else {
            startTask(lang, className, methodName, arguments, target, results, requirements, monitor);
        }
    }

    private void startMain(Lang lang, String className, String methodName, ApplicationParameter[] params,
        ApplicationParameter target, ApplicationParameter[] results, String ceiName,
        MethodResourceDescription requirements, AppMonitor monitor) {
        System.out.println(
            "Es vol executar el main " + lang + " " + className + "." + methodName + " parallelitzat amb " + ceiName);
        System.out.println("Parameters: ");
        for (ApplicationParameter param : params) {
            System.out.println("\t* " + param);
        }
        System.out.println("La tasca reservarà " + requirements);
        try {
            Agent.runMain(lang, ceiName, className, methodName, params, target, results, new Resource[0], monitor);
        } catch (AgentException ex) {
            ex.printStackTrace();
        }
    }

    private void startTask(Lang lang, String className, String methodName, ApplicationParameter[] params,
        ApplicationParameter target, ApplicationParameter[] results, MethodResourceDescription requirements,
        AppMonitor monitor) {

        try {
            Agent.runTask(lang, className, methodName, params, target, results, requirements, new Resource[0], monitor);
        } catch (AgentException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Main method that starts a COMPSs agent with a single interface through Comm.
     *
     * @param args arg[0] = port where the Comm Library sets the server
     * @throws Exception Error starting the COMPSs agent
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        CommAgentImpl cai = new CommAgentImpl();
        CommAgentConfig config = new CommAgentConfig(cai, port);
        Agent.startInterface(config);
        Agent.start();
    }

}
