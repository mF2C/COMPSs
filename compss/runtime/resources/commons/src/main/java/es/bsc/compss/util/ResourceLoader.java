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
package es.bsc.compss.util;

import es.bsc.compss.COMPSsConstants;
import es.bsc.compss.comm.Comm;
import es.bsc.compss.connectors.AbstractConnector;
import es.bsc.compss.exceptions.ConstructConfigurationException;
import es.bsc.compss.exceptions.NoResourceAvailableException;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.CloudProvider;
import es.bsc.compss.types.project.ProjectFile;
import es.bsc.compss.types.project.exceptions.ProjectFileValidationException;
import es.bsc.compss.types.project.jaxb.ApplicationType;
import es.bsc.compss.types.project.jaxb.AttachedDiskType;
import es.bsc.compss.types.project.jaxb.AttachedDisksListType;
import es.bsc.compss.types.project.jaxb.CloudPropertiesType;
import es.bsc.compss.types.project.jaxb.CloudPropertyType;
import es.bsc.compss.types.project.jaxb.CloudProviderType;
import es.bsc.compss.types.project.jaxb.CloudType;
import es.bsc.compss.types.project.jaxb.ComputeNodeType;
import es.bsc.compss.types.project.jaxb.DataNodeType;
import es.bsc.compss.types.project.jaxb.ImageType;
import es.bsc.compss.types.project.jaxb.ImagesType;
import es.bsc.compss.types.project.jaxb.InstanceTypeType;
import es.bsc.compss.types.project.jaxb.InstanceTypesType;
import es.bsc.compss.types.project.jaxb.MasterNodeType;
import es.bsc.compss.types.project.jaxb.MemoryType;
import es.bsc.compss.types.project.jaxb.OSType;
import es.bsc.compss.types.project.jaxb.PackageType;
import es.bsc.compss.types.project.jaxb.PriceType;
import es.bsc.compss.types.project.jaxb.ProcessorType;
import es.bsc.compss.types.project.jaxb.ServiceType;
import es.bsc.compss.types.project.jaxb.SoftwareListType;
import es.bsc.compss.types.project.jaxb.StorageType;
import es.bsc.compss.types.resources.DataResourceDescription;
import es.bsc.compss.types.resources.DynamicMethodWorker;
import es.bsc.compss.types.resources.MethodResourceDescription;
import es.bsc.compss.types.resources.MethodWorker;
import es.bsc.compss.types.resources.ResourcesFile;
import es.bsc.compss.types.resources.ServiceResourceDescription;
import es.bsc.compss.types.resources.ServiceWorker;
import es.bsc.compss.types.resources.configuration.MethodConfiguration;
import es.bsc.compss.types.resources.configuration.ServiceConfiguration;
import es.bsc.compss.types.resources.description.CloudImageDescription;
import es.bsc.compss.types.resources.description.CloudInstanceTypeDescription;
import es.bsc.compss.types.resources.exceptions.ResourcesFileValidationException;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.xml.sax.SAXException;


public class ResourceLoader {

    // Resources XML and XSD
    private static String resources_XML;
    private static String resources_XSD;

    // Project XML and XSD
    private static String project_XML;
    private static String project_XSD;

    // File instances (for cross validation)
    private static ResourcesFile resources;
    private static ProjectFile project;

    // Logger
    private static final Logger LOGGER = LogManager.getLogger(Loggers.RM_COMP);


    /**
     * Loads the information present in the given XML files according to the given XSD schemas.
     *
     * @param resourcesXML Resources XML file.
     * @param resourcesXSD Resources XSD schema.
     * @param projectXML Project XML file.
     * @param projectXSD Project XSD schema.
     * @throws ResourcesFileValidationException When resources file cannot be validated.
     * @throws ProjectFileValidationException When project file cannot be validated.
     * @throws NoResourceAvailableException When there is no resource available after parsing the given XML files.
     */
    public static void load(String resourcesXML, String resourcesXSD, String projectXML, String projectXSD)
        throws ResourcesFileValidationException, ProjectFileValidationException, NoResourceAvailableException {

        init(resourcesXML, resourcesXSD, projectXML, projectXSD);
        if ((ResourceLoader.resources_XML != null) && (ResourceLoader.project_XML != null)) {
            loadFiles();
            loadRuntime();
        } else {
            LOGGER.warn("No resource/project file detected. Starting runtime without computing resources.");
        }
    }

    private static void init(String resourcesXML, String resourcesXSD, String projectXML, String projectXSD) {
        LOGGER.info("ResourceLoader init");
        ResourceLoader.resources_XML = resourcesXML;
        ResourceLoader.resources_XSD = resourcesXSD;
        ResourceLoader.project_XML = projectXML;
        ResourceLoader.project_XSD = projectXSD;
    }

    private static void loadFiles() throws ResourcesFileValidationException, ProjectFileValidationException {
        LOGGER.info("ResourceLoader loading files");

        // Load resources
        try {
            LOGGER.debug("ResourceLoader loading resources.xml");
            ResourceLoader.resources = new ResourcesFile(new File(resources_XML), resources_XSD, LOGGER);
        } catch (SAXException | JAXBException e) {
            throw new ResourcesFileValidationException(e);
        }

        // Load project
        try {
            LOGGER.debug("ResourceLoader loading project.xml");
            ResourceLoader.project = new ProjectFile(new File(project_XML), project_XSD, LOGGER);
        } catch (SAXException | JAXBException e) {
            throw new ProjectFileValidationException(e);
        }
    }

    private static void loadRuntime() throws NoResourceAvailableException {
        LOGGER.info("ResourceLoader loading Runtime");

        /*
         * *************************************************************** Resources and project have been loaded and
         * validated We need to cross validate them and load the information in the runtime
         * 
         * WARNING: When the JAXB class is set by package information, it refers to the PROJECT.XML, when the JAXB class
         * is fully qualified name, it refers to the RESOURCES.XML
         */
        // Booleans to control initializated structures
        boolean computeNodeExist = false;
        boolean serviceExist = false;
        boolean cloudProviderExist = false;

        // Load master node information
        MasterNodeType master = ResourceLoader.project.getMasterNode();
        boolean exist = loadMaster(master);
        computeNodeExist = (computeNodeExist || exist);

        // Load ComputeNodes
        List<ComputeNodeType> computeNodes = ResourceLoader.project.getComputeNodes_list();
        if (computeNodes != null) {
            for (ComputeNodeType cnProject : computeNodes) {
                es.bsc.compss.types.resources.jaxb.ComputeNodeType cnResources =
                    ResourceLoader.resources.getComputeNode(cnProject.getName());
                if (cnResources != null) {
                    exist = loadComputeNode(cnProject, cnResources);
                    computeNodeExist = (computeNodeExist || exist);
                } else {
                    ErrorManager.warn("ComputeNode " + cnProject.getName() + " not defined in the resources file");
                }
            }
        }
        // Load Services
        List<ServiceType> services = ResourceLoader.project.getServices_list();
        if (services != null) {
            for (ServiceType sProject : services) {
                es.bsc.compss.types.resources.jaxb.ServiceType sResources =
                    ResourceLoader.resources.getService(sProject.getWsdl());
                if (sResources != null) {
                    exist = loadService(sProject, sResources);
                    serviceExist = (serviceExist || exist);
                } else {
                    ErrorManager.warn("Service " + sProject.getWsdl() + " not defined in the resources file");
                }
            }
        }

        // Load DataNodes
        List<DataNodeType> dataNodes = ResourceLoader.project.getDataNodes_list();
        if (dataNodes != null) {
            for (DataNodeType dnProject : dataNodes) {
                es.bsc.compss.types.resources.jaxb.DataNodeType dnResources =
                    ResourceLoader.resources.getDataNode(dnProject.getName());
                if (dnResources != null) {
                    loadDataNode(dnProject, dnResources);
                } else {
                    ErrorManager.warn("DataNode " + dnProject.getName() + " not defined in the resources file");
                }
            }
        }

        // Load Cloud
        CloudType cloud = ResourceLoader.project.getCloud();
        if (cloud != null) {
            cloudProviderExist = loadCloud(cloud);
        }

        // Availability checker
        if (!computeNodeExist && !serviceExist && !cloudProviderExist) {
            throw new NoResourceAvailableException();
        }
    }

    private static boolean loadMaster(MasterNodeType master) {
        Map<String, String> sharedDisks = new HashMap<>();
        List<Object> masterInformation = master.getProcessorOrMemoryOrStorage();
        MethodResourceDescription mrd = new MethodResourceDescription();
        if (masterInformation != null) {
            for (Object obj : masterInformation) {
                if (obj instanceof ProcessorType) {
                    ProcessorType procNode = (ProcessorType) obj;
                    String procName = procNode.getName();
                    int computingUnits = project.getProcessorComputingUnits(procNode);
                    String architecture = project.getProcessorArchitecture(procNode);
                    float speed = project.getProcessorSpeed(procNode);
                    String type = project.getProcessorType(procNode);
                    float internalMemory = project.getProcessorMemorySize(procNode);
                    es.bsc.compss.types.project.jaxb.ProcessorPropertyType procProp =
                        project.getProcessorProperty(procNode);
                    String propKey = (procProp != null) ? procProp.getKey() : "";
                    String propValue = (procProp != null) ? procProp.getValue() : "";
                    mrd.addProcessor(procName, computingUnits, architecture, speed, type, internalMemory, propKey,
                        propValue);
                } else {
                    if (obj instanceof MemoryType) {
                        MemoryType memNode = (MemoryType) obj;
                        mrd.setMemorySize(project.getMemorySize(memNode));
                        mrd.setMemoryType(project.getMemoryType(memNode));
                    } else {
                        if (obj instanceof StorageType) {
                            StorageType strNode = (StorageType) obj;
                            mrd.setStorageSize(project.getStorageSize(strNode));
                            mrd.setStorageType(project.getStorageType(strNode));
                            mrd.setStorageBW(project.getStorageBW(strNode));
                        } else {
                            if (obj instanceof OSType) {
                                OSType osNode = (OSType) obj;
                                mrd.setOperatingSystemType(project.getOperatingSystemType(osNode));
                                mrd.setOperatingSystemDistribution(project.getOperatingSystemDistribution(osNode));
                                mrd.setOperatingSystemVersion(project.getOperatingSystemVersion(osNode));
                            } else {
                                if (obj instanceof SoftwareListType) {
                                    SoftwareListType softwares = (SoftwareListType) obj;
                                    List<String> apps = softwares.getApplication();
                                    if (apps != null) {
                                        for (String appName : apps) {
                                            mrd.addApplication(appName);
                                        }
                                    }
                                } else {
                                    if (obj instanceof AttachedDisksListType) {
                                        AttachedDisksListType disks = (AttachedDisksListType) obj;
                                        if (disks != null) {
                                            List<AttachedDiskType> disksList = disks.getAttachedDisk();
                                            if (disksList != null) {
                                                for (AttachedDiskType disk : disksList) {
                                                    es.bsc.compss.types.resources.jaxb.SharedDiskType diskResources =
                                                        ResourceLoader.resources.getSharedDisk(disk.getName());
                                                    if (diskResources != null) {
                                                        // TODO: Check the disk information against the resources file
                                                        // (size and type)
                                                        String diskName = disk.getName();
                                                        String diskMountPoint = disk.getMountPoint();
                                                        sharedDisks.put(diskName, diskMountPoint);
                                                    } else {
                                                        ErrorManager.warn("SharedDisk " + disk.getName()
                                                            + " defined in the master node is not defined"
                                                            + " in the resources.xml. Skipping");
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        if (obj instanceof PriceType) {
                                            // TODO: Consider the price for the master node
                                        } else {
                                            // The XML is validated, we should never execute this part of code
                                            LOGGER.warn("MasterNode has an unrecognized parameter: " + obj.getClass());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Enabling Execution Capabilities (if any)
        ((DynamicMethodWorker) Comm.getAppHost()).increaseFeatures(mrd);

        // Registering Master's shared disks
        Comm.getAppHost().updateDisks(sharedDisks);
        for (Map.Entry<String, String> disk : sharedDisks.entrySet()) {
            SharedDiskManager.addSharedToMachine(disk.getKey(), disk.getValue(), Comm.getAppHost());
        }

        if (mrd.getTotalCPUComputingUnits() > 0) {
            ResourceManager.addDynamicWorker((DynamicMethodWorker) Comm.getAppHost(), mrd, null);
            return true;
        } else {
            return false;
        }
    }

    private static boolean loadComputeNode(ComputeNodeType cnProject,
        es.bsc.compss.types.resources.jaxb.ComputeNodeType cnResources) {

        // Add the name
        String name = cnProject.getName();

        /* Add properties given by the resources file **************************************** */
        MethodResourceDescription mrd = new MethodResourceDescription();
        List<es.bsc.compss.types.resources.jaxb.ProcessorType> processors = resources.getProcessors(cnResources);
        if (processors != null) {
            for (es.bsc.compss.types.resources.jaxb.ProcessorType p : processors) {
                String procName = p.getName();
                int computingUnits = resources.getProcessorComputingUnits(p);
                String architecture = resources.getProcessorArchitecture(p);
                float speed = resources.getProcessorSpeed(p);
                String type = resources.getProcessorType(p);
                float internalMemory = resources.getProcessorMemorySize(p);
                es.bsc.compss.types.resources.jaxb.ProcessorPropertyType procProp = resources.getProcessorProperty(p);
                String propKey = (procProp != null) ? procProp.getKey() : "";
                String propValue = (procProp != null) ? procProp.getValue() : "";
                mrd.addProcessor(procName, computingUnits, architecture, speed, type, internalMemory, propKey,
                    propValue);
            }
        }
        mrd.setMemorySize(resources.getMemorySize(cnResources));
        mrd.setMemoryType(resources.getMemoryType(cnResources));
        mrd.setStorageSize(resources.getStorageSize(cnResources));
        mrd.setStorageBW(resources.getStorageBW(cnResources));
        mrd.setStorageType(resources.getStorageType(cnResources));
        mrd.setOperatingSystemType(resources.getOperatingSystemType(cnResources));
        mrd.setOperatingSystemDistribution(resources.getOperatingSystemDistribution(cnResources));
        mrd.setOperatingSystemVersion(resources.getOperatingSystemVersion(cnResources));
        List<String> apps = resources.getApplications(cnResources);
        if (apps != null) {
            for (String appName : apps) {
                mrd.addApplication(appName);
            }
        }
        es.bsc.compss.types.resources.jaxb.PriceType p = resources.getPrice(cnResources);
        if (p != null) {
            mrd.setPriceTimeUnit(p.getTimeUnit());
            mrd.setPricePerUnit(p.getPricePerUnit());
        }

        // Add Shared Disks (Name, mountpoint)
        Map<String, String> sharedDisks = resources.getSharedDisks(cnResources);
        if (sharedDisks != null) {
            List<String> declaredSharedDisks = resources.getSharedDisks_names();
            for (String diskName : sharedDisks.keySet()) {
                if (declaredSharedDisks == null || !declaredSharedDisks.contains(diskName)) {
                    ErrorManager.warn("SharedDisk " + diskName + " defined in the ComputeNode " + name
                        + " is not defined in the resources.xml. Skipping");
                    sharedDisks.remove(diskName);
                    // TODO: Check the disk information (size and type)
                }
            }
        }

        // Add the adaptors properties (queue types and adaptor properties)
        // TODO Support multiple adaptor properties
        String loadedAdaptor = System.getProperty(COMPSsConstants.COMM_ADAPTOR);
        List<String> queuesProject = project.getAdaptorQueues(cnProject, loadedAdaptor);
        List<String> queuesResources = resources.getAdaptorQueues(cnResources, loadedAdaptor);
        if (queuesProject == null) {
            // Has no tag adaptors on project, get default resources complete
            for (String queue : queuesResources) {
                mrd.addHostQueue(queue);
            }
        } else {
            // Project defines a subset of queues
            for (String queue : queuesResources) {
                if (queuesProject.contains(queue)) {
                    mrd.addHostQueue(queue);
                }
            }
        }
        Map<String, Object> adaptorPropertiesProject = project.getAdaptorProperties(cnProject, loadedAdaptor);
        Map<String, Object> adaptorPropertiesResources = resources.getAdaptorProperties(cnResources, loadedAdaptor);

        MethodConfiguration config = null;
        try {
            config = (MethodConfiguration) Comm.constructConfiguration(loadedAdaptor, adaptorPropertiesProject,
                adaptorPropertiesResources);
        } catch (ConstructConfigurationException cce) {
            ErrorManager.warn("Adaptor " + loadedAdaptor + " configuration constructor failed", cce);
            return false;
        }

        // If we have reached this point the config is SURELY not null

        /* Add properties given by the project file **************************************** */
        config.setHost(cnProject.getName());
        config.setUser(project.getUser(cnProject));
        config.setInstallDir(project.getInstallDir(cnProject));
        config.setWorkingDir(project.getWorkingDir(cnProject));
        int limitOfTasks = project.getLimitOfTasks(cnProject);
        if (limitOfTasks >= 0) {
            config.setLimitOfTasks(limitOfTasks);
        } else {
            config.setLimitOfTasks(mrd.getTotalCPUComputingUnits());
        }
        config.setTotalComputingUnits(mrd.getTotalCPUComputingUnits());
        config.setTotalGPUComputingUnits(mrd.getTotalGPUComputingUnits());
        config.setTotalFPGAComputingUnits(mrd.getTotalFPGAComputingUnits());
        config.setTotalOTHERComputingUnits(mrd.getTotalOTHERComputingUnits());

        ApplicationType app = project.getApplication(cnProject);
        if (app != null) {
            config.setAppDir(app.getAppDir());
            config.setLibraryPath(app.getLibraryPath());
            config.setClasspath(app.getClasspath());
            config.setPythonpath(app.getPythonpath());
        }

        /* Pass all the information to the ResourceManager to insert it into the Runtime ** */
        LOGGER.debug("Adding method worker " + name);
        MethodWorker methodWorker = createMethodWorker(name, mrd, sharedDisks, config);
        ResourceManager.addStaticResource(methodWorker);
        // If we have reached this point the method worker has been correctly created
        return true;
    }

    private static boolean loadService(ServiceType sProject,
        es.bsc.compss.types.resources.jaxb.ServiceType sResources) {

        Map<String, Object> projectProperties = new HashMap<String, Object>();
        projectProperties.put("Service", sProject);

        Map<String, Object> resourcesProperties = new HashMap<String, Object>();
        resourcesProperties.put("Service", sResources);
        // Get service adaptor name from properties
        String serviceAdaptorName = COMPSsConstants.SERVICE_ADAPTOR;

        // Add the name
        String wsdl = sProject.getWsdl();

        /* Add properties given by the resources file **************************************** */
        final String serviceName = sResources.getName();
        final String namespace = sResources.getNamespace();
        final String port = sResources.getPort();
        final ServiceResourceDescription srd =
            new ServiceResourceDescription(serviceName, namespace, port, Integer.MAX_VALUE);

        ServiceConfiguration config = null;
        try {
            config = (ServiceConfiguration) Comm.constructConfiguration(serviceAdaptorName, projectProperties,
                resourcesProperties);
        } catch (ConstructConfigurationException cce) {
            ErrorManager.warn("Service configuration constructor failed", cce);
            return false;
        }

        // If we have reached this point the mp is SURELY not null

        /* Add properties given by the project file **************************************** */
        int limitOfTasks = sProject.getLimitOfTasks();
        if (limitOfTasks >= 0) {
            config.setLimitOfTasks(limitOfTasks);
        } else {
            config.setLimitOfTasks(Integer.MAX_VALUE);
        }

        /* Pass all the information to the ResourceManager to insert it into the Runtime ** */
        LOGGER.debug("Adding service worker " + wsdl);

        ServiceWorker newResource = new ServiceWorker(wsdl, srd, config);
        ResourceManager.addStaticResource(newResource);

        return true;
    }

    private static MethodWorker createMethodWorker(String name, MethodResourceDescription rd,
        Map<String, String> sharedDisks, MethodConfiguration mc) {

        // Compute task count
        int taskCount = getValidMinimum(mc.getLimitOfTasks(), rd.getTotalCPUComputingUnits());
        mc.setLimitOfTasks(taskCount);

        int taskCountGPU = getValidMinimum(mc.getLimitOfGPUTasks(), rd.getTotalGPUComputingUnits());
        mc.setLimitOfGPUTasks(taskCountGPU);

        int taskCountFPGA = getValidMinimum(mc.getLimitOfFPGATasks(), rd.getTotalFPGAComputingUnits());
        mc.setLimitOfFPGATasks(taskCountFPGA);

        int taskCountOther = getValidMinimum(mc.getLimitOfOTHERsTasks(), rd.getTotalOTHERComputingUnits());
        mc.setLimitOfOTHERsTasks(taskCountOther);

        // Create the method worker
        MethodWorker methodWorker = new MethodWorker(name, rd, mc, sharedDisks);
        return methodWorker;
    }

    private static int getValidMinimum(int x, int y) {
        if (x < 0 && y < 0) {
            return 0;
        }
        if (x < 0) {
            return y;
        }
        if (y < 0) {
            return x;
        }
        return Math.min(x, y);
    }

    private static boolean loadCloud(CloudType cloud) {
        Integer initialVMs = project.getInitialVMs(cloud);
        Integer minVMs = project.getMinVMs(cloud);
        Integer maxVMs = project.getMaxVMs(cloud);

        // Set parameters to CloudManager taking into account that if they are not defined we load default values
        ResourceManager.setCloudVMsBoundaries(minVMs, initialVMs, maxVMs);

        // Load cloud providers
        boolean cloudEnabled = false;
        List<String> cpResources = resources.getCloudProviders_names();
        for (CloudProviderType cp : project.getCloudProviders_list()) {
            if (cpResources.contains(cp.getName())) {
                // Resources contains information, loading cp
                boolean isEnabled = loadCloudProvider(cp, resources.getCloudProvider(cp.getName()));
                cloudEnabled = (cloudEnabled || isEnabled);
            } else {
                ErrorManager.warn("CloudProvider " + cp.getName() + " not defined in resources.xml file. Skipping");
            }
        }

        return cloudEnabled;
    }

    private static boolean loadCloudProvider(CloudProviderType cpProject,
        es.bsc.compss.types.resources.jaxb.CloudProviderType cpResources) {

        String cpName = cpProject.getName();
        String runtimeConnector = System.getProperty(COMPSsConstants.CONN);
        String connectorJarPath = "";
        String connectorMainClass = "";
        Map<String, String> properties = new HashMap<>();

        /* Add Endpoint information from resources.xml */
        es.bsc.compss.types.resources.jaxb.EndpointType endpoint = cpResources.getEndpoint();
        connectorJarPath = resources.getConnectorJarPath(endpoint);
        connectorMainClass = resources.getConnectorMainClass(endpoint);

        /* Add properties information ****************** */
        properties.put(AbstractConnector.PROP_SERVER, resources.getServer(endpoint));
        properties.put(AbstractConnector.PROP_PORT, resources.getPort(endpoint));
        List<Object> objList = cpProject.getImagesOrInstanceTypesOrLimitOfVMs();
        if (objList != null) {
            for (Object obj : objList) {
                if (obj instanceof CloudPropertiesType) {
                    CloudPropertiesType cloudProps = (CloudPropertiesType) obj;
                    for (CloudPropertyType prop : cloudProps.getProperty()) {
                        // TODO CloudProperties have context, name, value. Consider context (it is ignored now)
                        properties.put(prop.getName(), prop.getValue());
                    }
                }
            }
        }

        // Add application name property for some connectors (i.e. docker, vmm)
        String appName = System.getProperty(COMPSsConstants.APP_NAME);
        appName = appName.toLowerCase();
        appName = appName.replace('.', '-');
        properties.put(AbstractConnector.PROP_APP_NAME, appName);

        /* Add images/instances information ******************** */
        int limitOfVMs = -1;
        int maxCreationTime = -1; // Seconds
        LinkedList<CloudImageDescription> images = new LinkedList<>();
        LinkedList<CloudInstanceTypeDescription> instanceTypes = new LinkedList<>();
        objList = cpProject.getImagesOrInstanceTypesOrLimitOfVMs();
        if (objList != null) {
            for (Object obj : objList) {
                if (obj instanceof ImagesType) {
                    // Load images
                    ImagesType imageList = (ImagesType) obj;
                    for (ImageType imProject : imageList.getImage()) {
                        // Try to create image
                        es.bsc.compss.types.resources.jaxb.ImageType imResources =
                            resources.getImage(cpResources, imProject.getName());
                        CloudImageDescription cid = createImage(imProject, imResources, properties);

                        // Add to images list
                        if (cid != null) {
                            images.add(cid);
                            // Update max creation time
                            int localCT = cid.getCreationTime();
                            if (localCT > maxCreationTime) {
                                maxCreationTime = localCT;
                            }
                        }
                    }
                } else {
                    if (obj instanceof InstanceTypesType) {
                        // Load images
                        InstanceTypesType instancesList = (InstanceTypesType) obj;
                        for (InstanceTypeType instanceProject : instancesList.getInstanceType()) {
                            // Try to create instance
                            String instanceName = instanceProject.getName();
                            es.bsc.compss.types.resources.jaxb.InstanceTypeType instanceResources =
                                resources.getInstance(cpResources, instanceName);
                            if (instanceResources != null) {
                                CloudInstanceTypeDescription cmrd = createInstance(instanceResources);

                                // Add to instance list
                                if (cmrd != null) {
                                    instanceTypes.add(cmrd);
                                }
                            } else {
                                ErrorManager
                                    .warn("Instance " + instanceName + " not defined in resources.xml. Skipping");
                            }

                        }
                    } else {
                        if (obj instanceof Integer) { // Limit Of VMs
                            limitOfVMs = (Integer) obj;
                        }
                    }
                }
            }
        }
        if (maxCreationTime > 0) {
            properties.put(AbstractConnector.PROP_MAX_VM_CREATION_TIME, Integer.toString(maxCreationTime));
        }

        // Add Cloud Provider to CloudManager *****************************************/
        CloudProvider provider;
        try {
            provider = ResourceManager.registerCloudProvider(cpName, limitOfVMs, runtimeConnector, connectorJarPath,
                connectorMainClass, properties);
        } catch (Exception e) {
            ErrorManager.warn("Exception loading CloudProvider " + cpName, e);
            return false;
        }
        for (CloudImageDescription cid : images) {
            provider.addCloudImage(cid);
        }
        for (CloudInstanceTypeDescription instance : instanceTypes) {
            provider.addInstanceType(instance);
        }
        /* If we reach this point CP is loaded **************************************** */
        return true;
    }

    private static CloudImageDescription createImage(ImageType imProject,
        es.bsc.compss.types.resources.jaxb.ImageType imResources, Map<String, String> properties) {

        String imageName = imProject.getName();
        LOGGER.debug("Loading Image" + imageName);
        CloudImageDescription cid = new CloudImageDescription(imageName, properties);

        /* Add properties given by the resources file **************************************** */
        cid.setOperatingSystemType(resources.getOperatingSystemType(imResources));
        cid.setOperatingSystemDistribution(resources.getOperatingSystemDistribution(imResources));
        cid.setOperatingSystemVersion(resources.getOperatingSystemVersion(imResources));

        List<String> apps = resources.getApplications(imResources);
        if (apps != null) {
            for (String appName : apps) {
                cid.addApplication(appName);
            }
        }

        es.bsc.compss.types.resources.jaxb.PriceType p = resources.getPrice(imResources);
        if (p != null) {
            cid.setPriceTimeUnit(p.getTimeUnit());
            cid.setPricePerUnit(p.getPricePerUnit());
        }

        cid.setCreationTime(resources.getCreationTime(imResources));

        // Add Shared Disks (Name, mountpoint)
        Map<String, String> sharedDisks = resources.getSharedDisks(imResources);
        if (sharedDisks != null) {
            List<String> declaredSharedDisks = resources.getSharedDisks_names();
            for (String diskName : sharedDisks.keySet()) {
                if (declaredSharedDisks == null || !declaredSharedDisks.contains(diskName)) {
                    ErrorManager.warn("SharedDisk " + diskName + " defined in the Image " + imageName
                        + " is not defined in the resources.xml. Skipping");
                    sharedDisks.remove(diskName);
                    // TODO: Check the disk information (size and type)
                }
            }
        }
        cid.setSharedDisks(sharedDisks);

        // Add the adaptors properties (queue types and adaptor properties)
        // TODO Support multiple adaptor properties
        String loadedAdaptor = System.getProperty(COMPSsConstants.COMM_ADAPTOR);
        List<String> queuesProject = project.getAdaptorQueues(imProject, loadedAdaptor);
        List<String> queuesResources = resources.getAdaptorQueues(imResources, loadedAdaptor);
        for (String queue : queuesResources) {
            if (queuesProject.contains(queue)) {
                cid.addQueue(queue);
            }
        }
        Map<String, Object> adaptorPropertiesProject = project.getAdaptorProperties(imProject, loadedAdaptor);
        Map<String, Object> adaptorPropertiesResources = resources.getAdaptorProperties(imResources, loadedAdaptor);

        MethodConfiguration config = null;
        try {
            config = (MethodConfiguration) Comm.constructConfiguration(loadedAdaptor, adaptorPropertiesProject,
                adaptorPropertiesResources);
        } catch (ConstructConfigurationException cce) {
            ErrorManager.warn("Adaptor configuration constructor failed", cce);
            return null;
        }

        // If we have reached this point the mp is SURELY not null
        /* Add properties given by the project file **************************************** */
        config.setHost(imProject.getName());
        config.setUser(project.getUser(imProject));
        config.setInstallDir(project.getInstallDir(imProject));
        config.setWorkingDir(project.getWorkingDir(imProject));
        config.setLimitOfTasks(project.getLimitOfTasks(imProject));
        ApplicationType app = project.getApplication(imProject);
        if (app != null) {
            config.setAppDir(app.getAppDir());
            config.setLibraryPath(app.getLibraryPath());
            config.setClasspath(app.getClasspath());
            config.setPythonpath(app.getClasspath());
        }
        List<PackageType> packages = project.getPackages(imProject);
        for (PackageType pack : packages) {
            cid.addPackage(pack.getSource(), pack.getTarget());

            SoftwareListType software = pack.getIncludedSoftware();
            if (software != null) {
                cid.addAllApplications(software.getApplication());
            }
        }

        // Add configuration to image
        cid.setConfig(config);

        return cid;
    }

    private static CloudInstanceTypeDescription
        createInstance(es.bsc.compss.types.resources.jaxb.InstanceTypeType instance) {

        // Add the name
        // String name = instance.getName();
        final String type = instance.getName();
        final MethodResourceDescription mrd = new MethodResourceDescription();

        List<es.bsc.compss.types.resources.jaxb.ProcessorType> processors = resources.getProcessors(instance);
        if (processors != null) {
            for (es.bsc.compss.types.resources.jaxb.ProcessorType p : processors) {
                String procName = p.getName();
                int computingUnits = resources.getProcessorComputingUnits(p);
                String architecture = resources.getProcessorArchitecture(p);
                float speed = resources.getProcessorSpeed(p);
                String procType = resources.getProcessorType(p);
                float internalMemory = resources.getProcessorMemorySize(p);
                es.bsc.compss.types.resources.jaxb.ProcessorPropertyType procProp = resources.getProcessorProperty(p);
                String propKey = (procProp != null) ? procProp.getKey() : "";
                String propValue = (procProp != null) ? procProp.getValue() : "";
                mrd.addProcessor(procName, computingUnits, architecture, speed, procType, internalMemory, propKey,
                    propValue);
            }
        }

        mrd.setMemorySize(resources.getMemorySize(instance));
        mrd.setMemoryType(resources.getMemoryType(instance));

        mrd.setStorageSize(resources.getStorageSize(instance));
        mrd.setStorageType(resources.getStorageType(instance));
        mrd.setStorageBW(resources.getStorageBW(instance));

        es.bsc.compss.types.resources.jaxb.PriceType p = resources.getPrice(instance);
        if (p != null) {
            mrd.setPriceTimeUnit(p.getTimeUnit());
            mrd.setPricePerUnit(p.getPricePerUnit());
        }

        return new CloudInstanceTypeDescription(type, mrd);
    }

    private static void loadDataNode(DataNodeType dnProject,
        es.bsc.compss.types.resources.jaxb.DataNodeType dnResources) {

        String host = resources.getHost(dnResources);
        String path = resources.getPath(dnResources);

        /* Add properties given by the resources file **************************************** */
        DataResourceDescription dd = new DataResourceDescription(host, path);
        dd.setStorageSize(resources.getStorageSize(dnResources));
        dd.setStorageType(resources.getStorageType(dnResources));
        dd.setStorageBW(resources.getStorageBW(dnResources));

        // Add the name
        String name = dnProject.getName();

        // Add the adaptors properties (queue types and adaptor properties)
        // TODO Support adaptor properties on DataNodes
        /* Pass all the information to the ResourceManager to insert it into the Runtime ** */
        // TODO Insert DataNode into the runtime
        LOGGER.warn("Cannot load DataNode " + name + ". DataNodes are not supported inside the Runtime");
        // LOGGER.debug("Adding data worker " + name);
        // ResourceManager.newDataWorker(name, dd);
    }

}
