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
package es.bsc.compss.types.data;

import es.bsc.compss.comm.Comm;
import es.bsc.compss.data.BindingDataManager;
import es.bsc.compss.exceptions.CannotLoadException;
import es.bsc.compss.log.Loggers;
import es.bsc.compss.types.BindingObject;
import es.bsc.compss.types.data.listener.SafeCopyListener;
import es.bsc.compss.types.data.location.BindingObjectLocation;
import es.bsc.compss.types.data.location.DataLocation;
import es.bsc.compss.types.data.location.PersistentLocation;
import es.bsc.compss.types.data.location.ProtocolType;
import es.bsc.compss.types.data.operation.copy.Copy;
import es.bsc.compss.types.resources.Resource;
import es.bsc.compss.types.uri.MultiURI;
import es.bsc.compss.types.uri.SimpleURI;
import es.bsc.compss.util.ErrorManager;
import es.bsc.compss.util.Serializer;
import es.bsc.compss.util.SharedDiskManager;
import es.bsc.compss.util.TraceEvent;
import es.bsc.compss.util.Tracer;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import storage.StorageException;
import storage.StorageItf;


public class LogicalData {

    // Logger
    private static final Logger LOGGER = LogManager.getLogger(Loggers.COMM);
    private static final boolean DEBUG = LOGGER.isDebugEnabled();
    private static final String DBG_PREFIX = "[LogicalData] ";
    // Logical data name
    private final String name;

    // Is value stored in Memory
    private boolean inMemory;

    // Value in memory
    private Object value;
    // Id if PSCO, null otherwise
    private String pscoId;
    // Id if Binding object, null otherwise
    private String bindingId;

    // List of existing copies
    private final Set<DataLocation> locations = new TreeSet<>();
    // In progress
    private final List<CopyInProgress> inProgress = new LinkedList<>();
    // File's size.
    private float size;

    // Indicates if LogicalData has been ordered to save before
    private boolean isBeingSaved;
    private boolean isBindingData;
    // Locks the host while LogicalData is being copied
    private final Semaphore lockHostRemoval = new Semaphore(1);


    /*
     * Constructors
     */
    /**
     * Constructs a LogicalData for a given data version.
     *
     * @param name Data name
     */
    public LogicalData(String name) {
        this.name = name;
        this.inMemory = false;
        this.value = null;
        this.pscoId = null;
        this.bindingId = null;
        this.isBeingSaved = false;
        this.isBindingData = false;
        this.size = 0;
    }

    /*
     * Getters
     */
    /**
     * Returns the data version name.
     *
     * @return
     */
    public String getName() {
        // No need to sync because it cannot be modified
        return this.name;
    }

    /**
     * Returns the PSCO id. Null if its not a PSCO
     *
     * @return
     */
    public String getPscoId() {
        return this.pscoId;
    }

    /**
     * Returns all the hosts that contain a data location.
     *
     * @return
     */
    public synchronized Set<Resource> getAllHosts() {
        Set<Resource> list = new HashSet<>();
        for (DataLocation loc : this.locations) {
            List<Resource> hosts = loc.getHosts();
            synchronized (hosts) {
                list.addAll(hosts);
            }
        }

        return list;
    }

    /**
     * Adds a new location.
     *
     * @param loc New location
     */
    public synchronized void addLocation(DataLocation loc) {
        this.isBeingSaved = false;
        this.locations.add(loc);
        switch (loc.getType()) {
            case PRIVATE:
                for (Resource r : loc.getHosts()) {
                    r.addLogicalData(this);
                }
                break;
            case BINDING:
                for (Resource r : loc.getHosts()) {
                    this.isBindingData = true;
                    if (this.bindingId == null) {
                        this.bindingId = ((BindingObjectLocation) loc).getId();
                    }
                    r.addLogicalData(this);
                }
                break;
            case SHARED:
                SharedDiskManager.addLogicalData(loc.getSharedDisk(), this);
                break;
            case PERSISTENT:
                this.pscoId = ((PersistentLocation) loc).getId();
                break;
        }
    }

    /**
     * Obtain the all the URIs.
     *
     * @return
     */
    public synchronized List<MultiURI> getURIs() {
        List<MultiURI> list = new LinkedList<>();
        for (DataLocation loc : this.locations) {
            List<MultiURI> locationURIs = loc.getURIs();
            // Adds all the valid locations
            if (locationURIs != null) {
                list.addAll(locationURIs);
            }
        }

        return list;
    }

    /**
     * Obtain all URIs in a resource.
     * 
     * @param targetHost Resource
     * @return list of uri where data is located in the node
     */
    public synchronized List<MultiURI> getURIsInHost(Resource targetHost) {
        List<MultiURI> list = new LinkedList<>();
        for (DataLocation loc : this.locations) {
            MultiURI locationURI = loc.getURIInHost(targetHost);
            if (locationURI != null) {
                list.add(locationURI);
            }
        }
        return list;
    }

    public synchronized Set<DataLocation> getLocations() {
        return this.locations;
    }

    public synchronized void setSize(float size) {
        this.size = size;
    }

    public float getSize() {
        return this.size;
    }

    /**
     * Returns if the data value is stored in memory or not.
     *
     * @return
     */
    public synchronized boolean isInMemory() {
        return this.inMemory;
    }

    /**
     * Returns if the data is binding data.
     *
     * @return
     */
    public synchronized boolean isBindingData() {
        return isBindingData;
    }

    /**
     * Returns the value stored in memory.
     *
     * @return
     */
    public synchronized Object getValue() {
        return this.value;
    }

    /*
     * Setters
     */
    /**
     * Removes the object from master main memory and removes its location.
     *
     * @return
     */
    public synchronized Object removeValue() {
        DataLocation loc = null;
        String targetPath = ProtocolType.OBJECT_URI.getSchema() + this.name;
        try {
            SimpleURI uri = new SimpleURI(targetPath);
            loc = DataLocation.createLocation(Comm.getAppHost(), uri);
        } catch (Exception e) {
            ErrorManager.error(DataLocation.ERROR_INVALID_LOCATION + " " + targetPath, e);
        }

        Object val;
        val = this.value;
        this.value = null;
        this.inMemory = false;
        // Removes only the memory location (no need to check private, shared,
        // persistent)
        this.locations.remove(loc);

        return val;
    }

    /**
     * Sets the memory value.
     *
     * @param o Object value
     */
    public synchronized void setValue(Object o) {
        this.value = o;
        this.inMemory = true;
    }

    /**
     * Sets the PSCO id of a logical data.
     *
     * @param id PSCO Identifier
     */
    public synchronized void setPscoId(String id) {
        this.pscoId = id;
    }

    /**
     * Writes memory value to file.
     *
     * @throws Exception Error writting to storage
     */
    public synchronized void writeToStorage() throws Exception {
        if (DEBUG) {
            LOGGER.debug(DBG_PREFIX + "Writting object " + this.name + " to storage");
        }
        if (isBindingData) {
            String targetPath = Comm.getAppHost().getWorkingDirectory() + this.name;
            String id;
            // decide the id where the object is stored in the binding
            if (this.bindingId != null) {
                id = this.bindingId;
            } else {
                if (this.value != null) {
                    id = (String) this.value;
                } else {
                    id = this.name;
                }
            }
            if (id.contains("#")) {
                id = BindingObject.generate(id).getName();
            }
            if (BindingDataManager.isInBinding(id)) {
                if (DEBUG) {
                    LOGGER.debug(DBG_PREFIX + "Writting binding object " + id + " to file " + targetPath);
                }
                BindingDataManager.storeInFile(id, targetPath);
                addWrittenObjectLocation(targetPath);
            } else {
                LOGGER.error(DBG_PREFIX + " Error " + id + " not found in binding");
                throw (new Exception(" Error " + id + " not found in binding"));
            }
        } else {
            if (this.pscoId != null) {
                // It is a persistent object that is already persisted
                // Nothing to do
                // If the PSCO is not persisted we treat it as a normal object
            } else {

                // The object must be written to file
                String targetPath = Comm.getAppHost().getWorkingDirectory() + this.name;
                if (DEBUG) {
                    LOGGER.debug(DBG_PREFIX + "Writting object " + this.name + " to file " + targetPath);
                }
                Serializer.serialize(value, targetPath);
                addWrittenObjectLocation(targetPath);
            }
        }
        if (DEBUG) {
            LOGGER.debug(DBG_PREFIX + "Object " + this.name + " written to storage");
        }
    }

    private void addWrittenObjectLocation(String targetPath) throws IOException {
        String targetPathWithSchema = ProtocolType.FILE_URI.getSchema() + targetPath;
        SimpleURI targetURI = new SimpleURI(targetPathWithSchema);
        DataLocation loc = DataLocation.createLocation(Comm.getAppHost(), targetURI);
        this.isBeingSaved = false;
        this.locations.add(loc);
        for (Resource r : loc.getHosts()) {
            switch (loc.getType()) {
                case BINDING:
                case PRIVATE:
                    r.addLogicalData(this);
                    break;
                case SHARED:
                    SharedDiskManager.addLogicalData(loc.getSharedDisk(), this);
                    break;
                case PERSISTENT:
                    // Nothing to do
                    break;
            }
        }
    }

    /**
     * Loads the value of the LogicalData from a file.
     *
     * @throws CannotLoadException Error loading from storage
     */
    public synchronized void loadFromStorage() throws CannotLoadException {
        // TODO: Check if we have to do something in binding data??
        if (inMemory) {
            // Value is already loaded in memory
            return;
        }

        if (this.pscoId != null) {
            LOGGER.info("Data was on the persistent storage. Fetching it from there!");
            if (Tracer.extraeEnabled()) {
                Tracer.emitEvent(TraceEvent.STORAGE_GETBYID.getId(), TraceEvent.STORAGE_GETBYID.getType());
            }
            try {
                this.value = StorageItf.getByID(pscoId);
                inMemory = true;
            } catch (StorageException se) {
                // Check next location since cannot retrieve the object from the storage Back-end
                ErrorManager.warn("Could not load the value from the persistent storage.", se);
            } finally {
                if (Tracer.extraeEnabled()) {
                    Tracer.emitEvent(Tracer.EVENT_END, TraceEvent.STORAGE_GETBYID.getType());
                }
            }
            if (inMemory) {
                String targetPath = ProtocolType.OBJECT_URI.getSchema() + this.name;
                SimpleURI uri = new SimpleURI(targetPath);
                try {
                    DataLocation tgtLoc = DataLocation.createLocation(Comm.getAppHost(), uri);
                    addLocation(tgtLoc);
                    return;
                } catch (IOException e) {
                    ErrorManager.warn("Could not register object location", e);
                    // Check next location since location was invalid
                    this.value = null;
                    inMemory = false;
                }
            }
        }

        for (DataLocation loc : this.locations) {
            switch (loc.getType()) {
                case PRIVATE:
                case SHARED:
                    // Get URI and deserialize object if possible
                    MultiURI u = loc.getURIInHost(Comm.getAppHost());
                    if (u == null) {
                        continue;
                    }

                    String path = u.getPath();
                    if (path.startsWith(File.separator)) {
                        try {
                            this.value = Serializer.deserialize(path);
                            inMemory = true;
                        } catch (ClassNotFoundException | IOException e) {
                            // Check next location since deserialization was invalid
                            this.value = null;
                            continue;
                        }

                        String targetPath = ProtocolType.OBJECT_URI.getSchema() + this.name;
                        SimpleURI uri = new SimpleURI(targetPath);
                        try {
                            DataLocation tgtLoc = DataLocation.createLocation(Comm.getAppHost(), uri);
                            addLocation(tgtLoc);
                        } catch (IOException e) {
                            // Check next location since location was invalid
                            this.value = null;
                            continue;
                        }
                    }

                    return;
                case PERSISTENT:
                    // Should already have been detected earlier
                case BINDING:
                    // We should never reach this
                    throw new CannotLoadException("ERROR: Trying to load from storage a BINDING location");
            }
        }

        // Any location has been able to load the value
        throw new CannotLoadException("Object has not any valid location available in the master");
    }

    /**
     * Removes all the locations assigned to a given host and returns a valid location if the file is unique.
     *
     * @param host Resource
     * @param sharedMountPoints Shared mount point
     * @return a valid location if the file is unique
     */
    public synchronized DataLocation removeHostAndCheckLocationToSave(Resource host,
        Map<String, String> sharedMountPoints) {
        // If the file is being saved means that this function has already been
        // executed
        // for the same LogicalData. Thus, all the host locations are already
        // removed
        // and there is no unique file to save
        if (isBeingSaved) {
            return null;
        }
        // Otherwise, we must remove all the host locations and store a unique
        // location if needed. We only store the "best" location if any (by
        // choosing
        // any private location found or the first shared location)
        DataLocation uniqueHostLocation = null;
        Iterator<DataLocation> it = this.locations.iterator();
        while (it.hasNext()) {
            DataLocation loc = it.next();
            switch (loc.getType()) {
                case BINDING:
                case PRIVATE:
                    if (loc.getURIInHost(host) != null) {
                        this.isBeingSaved = true;
                        uniqueHostLocation = loc;
                        it.remove();
                    }
                    break;
                case SHARED:
                    // When calling this function the host inside the
                    // SharedDiskManager has been removed
                    // If there are no remaining hosts it means it was the last
                    // host thus, the location
                    // is unique and must be saved
                    if (loc.getHosts().isEmpty()) {
                        String sharedDisk = loc.getSharedDisk();
                        if (sharedDisk != null) {
                            String mountPoint = sharedMountPoints.get(sharedDisk);
                            if (mountPoint != null) {
                                if (uniqueHostLocation == null) {
                                    this.isBeingSaved = true;

                                    String targetPath = ProtocolType.FILE_URI.getSchema() + loc.getPath();
                                    try {
                                        SimpleURI uri = new SimpleURI(targetPath);
                                        uniqueHostLocation = DataLocation.createLocation(host, uri);
                                    } catch (Exception e) {
                                        ErrorManager.error(DataLocation.ERROR_INVALID_LOCATION + " " + targetPath, e);
                                    }
                                }
                            }
                        }
                    }
                    break;
                case PERSISTENT:
                    // Persistent location must never be saved
                    break;
            }
        }
        return uniqueHostLocation;
    }

    /**
     * Returns the copies in progress.
     *
     * @return
     */
    public synchronized Collection<Copy> getCopiesInProgress() {
        List<Copy> copies = new LinkedList<>();
        for (CopyInProgress cp : this.inProgress) {
            copies.add(cp.getCopy());
        }

        return copies;
    }

    /**
     * Returns if the data is already available in a given targetHost.
     *
     * @param targetHost target resource
     * @return
     */
    public synchronized MultiURI alreadyAvailable(Resource targetHost) {
        for (DataLocation loc : locations) {
            MultiURI u = loc.getURIInHost(targetHost);
            // If we have found a valid location, return it
            if (u != null) {
                return u;
            }
        }

        // All locations are invalid
        return null;
    }

    /**
     * Returns if a copy of the LogicalData is being performed to a target host.
     *
     * @param target Target location
     * @return the copy in progress or null if none
     */
    public synchronized Copy alreadyCopying(DataLocation target) {
        for (CopyInProgress cip : this.inProgress) {
            if (cip.hasTarget(target)) {
                return cip.getCopy();
            }
        }

        return null;
    }

    /**
     * Begins a copy of the LogicalData to a target host.
     *
     * @param c Copy
     * @param target Target data location
     */
    public synchronized void startCopy(Copy c, DataLocation target) {
        this.inProgress.add(new CopyInProgress(c, target));
    }

    /**
     * Marks the end of a copy. Returns the location of the finished copy or null if not found.
     *
     * @param c Copy
     * @return
     */
    public synchronized DataLocation finishedCopy(Copy c) {
        DataLocation loc = null;

        Iterator<CopyInProgress> it = this.inProgress.iterator();
        while (it.hasNext()) {
            CopyInProgress cip = it.next();
            if (cip.c == c) {
                it.remove();
                loc = cip.loc;
                break;
            }
        }

        return loc;
    }

    /**
     * Adds a listener to the inProgress copies.
     *
     * @param listener Copy listener
     */
    public synchronized void notifyToInProgressCopiesEnd(SafeCopyListener listener) {
        for (CopyInProgress cip : this.inProgress) {
            listener.addOperation();
            cip.c.addEventListener(listener);
        }
    }

    /**
     * Sets the LogicalData as obsolete.
     */
    public synchronized void isObsolete() {
        for (Resource res : this.getAllHosts()) {
            res.addObsolete(this);
        }
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Logical Data name: ").append(this.name).append("\n");
        sb.append("\t Value: ").append(value).append("\n");
        sb.append("\t Id: ").append(pscoId).append("\n");
        sb.append("\t Locations:\n");
        synchronized (locations) {
            for (DataLocation dl : locations) {
                sb.append("\t\t * ").append(dl).append("\n");
            }
        }
        return sb.toString();
    }


    /*
     * Copy in progress class to extend external copy
     */
    private static class CopyInProgress {

        private final Copy c;
        private final DataLocation loc;


        public CopyInProgress(Copy c, DataLocation loc) {
            this.c = c;
            this.loc = loc;
        }

        public Copy getCopy() {
            return this.c;
        }

        private boolean hasTarget(DataLocation target) {
            return loc.isTarget(target);
        }

        @Override
        public String toString() {
            return c.getName() + " to " + loc.toString();
        }

    }

}
