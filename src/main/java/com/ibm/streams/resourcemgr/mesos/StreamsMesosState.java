// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ibm.streams.resourcemgr.mesos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.streams.resourcemgr.ClientInfo;
import com.ibm.streams.resourcemgr.ResourceDescriptor;
import com.ibm.streams.resourcemgr.ResourceException;
import com.ibm.streams.resourcemgr.ResourceManagerException;
import com.ibm.streams.resourcemgr.ResourcePersistenceManager;
import com.ibm.streams.resourcemgr.ResourceTagException;
import com.ibm.streams.resourcemgr.ResourceTags;
import com.ibm.streams.resourcemgr.ResourceTags.TagDefinitionType;
import com.ibm.streams.resourcemgr.mesos.StreamsMesosResource.RequestState;
import com.ibm.streams.resourcemgr.mesos.StreamsMesosResource.ResourceState;

// For client persistence
import com.ibm.streams.resourcemgr.request.JsonUtilities;

/**
 * Contains the state of the Resource manager
 * 
 * Future: Primary interface with ResourcePersistenceManager
 *
 */

public class StreamsMesosState {

	private static final Logger LOG = LoggerFactory.getLogger(StreamsMesosState.class);
	
	private StreamsMesosResourceManager _manager;
	private StreamsMesosScheduler _scheduler;
	private ResourcePersistenceManager _persistence;

	/* StreamsMesosResource containers */

	// allResources: Tracks all resources no matter what state (e.g. requested,
	// running, etc.)
	// indexed by id that we generate
	// Using concurrentHashMap.  simplifies concurrency between the threads of the this class and the mesos scheduler
	private Map<String, StreamsMesosResource> _allResources;
	
	// requestedResources: Tracks new requests from Streams and checked by scheduler
	// when new offers arrive
	// Using concurrent CopyOnWriteArrayList.  It is slower than ArrayList but at the rate that 
	// we add/remove resources and requests it is more than fast enough and simplifies concurrency
	private List<StreamsMesosResource> _requestedResources;

	// Streams Client Information
	// NOTE: We only handle a single Controller client at this time
	private ClientInfo _clientInfo;
	
	/**
	 * Constructor
	 */
	public StreamsMesosState(StreamsMesosResourceManager manager) {
		LOG.trace("StreamsMesosState being constructed");
		_manager = manager;
		_scheduler = null;
		_allResources = new ConcurrentHashMap<String, StreamsMesosResource>();
		_requestedResources = new CopyOnWriteArrayList<StreamsMesosResource>();
		LOG.trace("Connecting to Streams ResourcePersistenceManager");
		_persistence = _manager.getResourcePersistenceManager();
		_persistence.connect();
		_clientInfo = null;

	}

	
	public void setScheduler(StreamsMesosScheduler _scheduler) {
		this._scheduler = _scheduler;
	}

	// Create a new SMR and put it proper containers
	synchronized public StreamsMesosResource createNewResource(ClientInfo client, ResourceTags tags, boolean isMaster) throws ResourceManagerException {
        LOG.trace("createNewResource clientId: " + client.getClientId() + ", isMaster: " + isMaster + ", tags: " + tags);
		// Create the Resource object (default state is NEW)
		StreamsMesosResource smr = new StreamsMesosResource(Utils.generateNextId("resource"), client, _manager);

		smr.setMaster(isMaster);
		
		// Set default resource needs
		double memory = Utils.getDoubleProperty(_manager.getConfig(), StreamsMesosConstants.PROPS_DC_MEMORY);
		double cores = Utils.getDoubleProperty(_manager.getConfig(), StreamsMesosConstants.PROPS_DC_CORES);

		smr.setMemory(memory);
		smr.setCpu(cores);

		// Set the resource tags which may override defaults for memory and cores
		if (tags != null) {
			convertTags(tags, smr); // may set mem/cpu from the tags if they are specified
			smr.getTags().addAll(tags.getNames());
		}
		
		LOG.debug("QUEUING new Resource Request: " + smr.toString());
		
		// create allResources map entry
		_allResources.put(smr.getId(), smr);
		
		// persist resource
		persistResource(smr);
		
		// put resource into requested array
		requestResource(smr);
		
		// Save client information that requested the resource
		addClientInfo(client, smr.getId());
		
		return smr;
	}
	
	/** 
	 * @param tags
	 * @param smr
	 * @throws ResourceTagException
	 * @throws ResourceManagerException
	 */
	private void convertTags(ResourceTags tags, StreamsMesosResource smr) throws ResourceTagException, ResourceManagerException {
		double cores = -1;
		double memory = -1;
		
		for (String tag : tags.getNames()) {
			try {
				TagDefinitionType definitionType = tags.getTagDefinitionType(tag);
				
				switch (definitionType) {
				case NONE:
					// use default definition (probably just a name tag (e.g. AUDIT)
					break;
				case PROPERTIES:
					Properties propsDef = tags.getDefinitionAsProperties(tag);
					LOG.trace("Tag=" + tag + " props=" + propsDef.toString());
					if (propsDef.containsKey(StreamsMesosConstants.MEMORY_TAG)) {
						//memory = Math.max(memory,  Utils.getIntProperty(propsDef, StreamsMesosConstants.MEMORY_TAG));
						memory = Utils.getDoubleProperty(propsDef, StreamsMesosConstants.MEMORY_TAG);
						LOG.trace("Tag=" + tag + " memory=" + memory);
					}
					if (propsDef.containsKey(StreamsMesosConstants.CORES_TAG)) {
						//cores = Math.max(cores,  Utils.getDoubleProperty(propsDef, StreamsMesosConstants.CORES_TAG));
						cores = Utils.getDoubleProperty(propsDef, StreamsMesosConstants.CORES_TAG);
						LOG.trace("Tag=" + tag + " cores=" + cores);
					}
					break;
				default:
					throw new ResourceTagException("Tag=" + tag + " has unsupported tag definition type=" + definitionType);
				}
			} catch (ResourceException rs) {
				throw new ResourceManagerException(rs);
			}
		}
		
		// Override memory and cores if they were set by the tags
		if (memory != -1){
			smr.setMemory(memory);
		}
		if (cores != -1) {
			smr.setCpu(cores);
		}
	}
	
	// Re-request resource means put it back on the requestedResources list
	// Usually called when a failure occurs before Streams notified
	// Example is a problem with mesos slave that prevents streams controller from running
	private void reLaunchResourceTask(StreamsMesosResource smr) {
		LOG.debug("Re-launching resource task" + smr.getId());
		smr.setResourceState(ResourceState.NEW);
		smr.setTaskCompletionStatus(null);
		smr.setTaskId(null);
		persistResource(smr);
		requestResource(smr);
	}
	
	private void requestResource(StreamsMesosResource smr) {
		synchronized(this) {
			// add to requested resources array
			_requestedResources.add(smr);
			
			persistResourceRequest(smr.getId());
			
			if (_scheduler != null) {
				if (!_scheduler.isReceivingOffers()) {
					LOG.debug("Reviving offers from mesos");
					_scheduler.reviveOffers();
				}
			}
		}
	}
	
	public Map<String, StreamsMesosResource> getAllResources() {
		return _allResources;
	}
	
	// Return list of new Reqeusts as an immutable list
	public List<StreamsMesosResource> getRequestedResources() {
		return _requestedResources;
	}
	
	// Remove a resource from the list of requested resources if it is there
	// This does not delete the resource, just means the request is no longer outstanding
	public void removeRequestedResource(StreamsMesosResource smr) {
		_requestedResources.remove(smr);
		
		deleteResourceRequest(smr.getId());
	}
	
	// Remove a collection of requested resources
	public void removeRequestedResources(Collection<StreamsMesosResource> resources) {
		_requestedResources.removeAll(resources);
		
		for (StreamsMesosResource smr: resources) {
			deleteResourceRequest(smr.getId());
		}
	}
	
	// Return resourceId
	// Just a place where we can control how we index our resources in persistence, now it is by nativeResourceId same as resourceId
	public String getResourceId(String nativeResourceId) {
		return nativeResourceId;
	}
	
	// Return resourceId associated with the resource descriptor from Streams
	public String getResourceId(ResourceDescriptor descriptor) {
		return getResourceId(descriptor.getNativeResourceId());
	}
	
	// Get Resource, if not in map try persistence
	public StreamsMesosResource getResource(String resourceId) {
		StreamsMesosResource smr = null;
		
		if (_allResources.containsKey(resourceId)) {
			smr =  _allResources.get(resourceId);
		} else {
			// Not in the map, try persistence
			smr = retrieveResource(resourceId);
			
			if (smr != null) {
				LOG.debug("getResource(" + resourceId + ") not found in _allResources map, but did find in persistence, adding to map");
				_allResources.put(resourceId, smr);
			}
		}
		
		if (smr == null) {
			LOG.warn("getResource from state failed: Resource Not found (id: " + resourceId  + ")");
		}
	
		return smr;
	}
	
	// Get resource for a given taskId.
	// Future: May need to retrieve from persistence for failover, recovery, and reconciliation
	// Future: May need an index in persistence tasks/<taskId>: {resourceId: <resourceId>}
	private StreamsMesosResource getResourceByTaskId(String taskId) {
		for (StreamsMesosResource smr : _allResources.values()) {
			if (smr.getTaskId() != null) {
				if (smr.getTaskId().equals(taskId)) {
					return smr;
				} 
			}
		}
		// Not found
		LOG.warn("getResourceByTaskId from state failed: Resource Not found.  May not be Launched yet. (TaskID: " + taskId + ")");
		return null;
	}
	
	// Task launched.  Reported by Scheduler when it has launched the task
	// Future: May need to insert into index in persistence tasks/<taskId>: {resourceId: <resourceId>}
	public void taskLaunched(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setResourceState(StreamsMesosResource.ResourceState.LAUNCHED);
			smr.setTaskCompletionStatus(StreamsMesosResource.TaskCompletionStatus.NONE);
			persistResource(smr);
		} else {
			LOG.warn("taskLaunched from state failed to find resource (id: " + resourceId + ")");
		}

	}
	
	// Set Allocated.  Reported by Resource Manager when pending requests are satisfied
	public void setAllocated(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setRequestState(StreamsMesosResource.RequestState.ALLOCATED);
			persistResource(smr);
		} else {
			LOG.warn("setAllocated from state failed to find resource (id: " + resourceId + ")");
		}		
	}
	
	// Set Pending.  Reported by Resource Manager when request can not be immediately satisfied
	public void setPending(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setRequestState(StreamsMesosResource.RequestState.PENDING);
			persistResource(smr);
		} else {
			LOG.warn("setPending from state failed to find resource (id: " + resourceId + ")");
		}
	}
	
	
	///////////////////////////////////////
	/// TASK STATUS FROM MESOS HANDLING
	///////////////////////////////////////
	
	// Map mesos task status to our resource state
	// Future: May need ability to create new resources when we reconcile after a failure
	private void mapAndUpdateMesosTaskStateToResourceState(StreamsMesosResource smr, Protos.TaskState taskState) {
		// Handle the mapping and interpretation of mesos status update
	
		StreamsMesosResource.ResourceState newResourceState = null;
		StreamsMesosResource.TaskCompletionStatus newTaskCompletionStatus = null;
		
		
		switch (taskState) {
		case TASK_STAGING:
		case TASK_STARTING:
			newResourceState = StreamsMesosResource.ResourceState.LAUNCHED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.NONE;
			break;
		case TASK_RUNNING:
			newResourceState = StreamsMesosResource.ResourceState.RUNNING;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.NONE;
			break;
		case TASK_FINISHED:
			newResourceState = StreamsMesosResource.ResourceState.STOPPED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.FINISHED;
			break;
		case TASK_ERROR:
			newResourceState = StreamsMesosResource.ResourceState.FAILED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.ERROR;
			break;	
		case TASK_KILLED:
			newResourceState = StreamsMesosResource.ResourceState.FAILED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.KILLED;
			break;
		case TASK_LOST:
			newResourceState = StreamsMesosResource.ResourceState.FAILED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.LOST;
			break;
		case TASK_FAILED:
			newResourceState = StreamsMesosResource.ResourceState.FAILED;
			newTaskCompletionStatus = StreamsMesosResource.TaskCompletionStatus.FAILED;
			break;
		default:
			LOG.info("Mesos Scheduler notified of task status of " + smr.getTaskId() + " change to " + taskState + ", no action taken.");
			newResourceState = null;
			newTaskCompletionStatus = null;
			break;
		}
		
		
		if (newResourceState != null) {
			LOG.debug("Mesos Task Status Update Mapped: Resource ID: " + smr.getId() + ", Resource State = " + newResourceState.toString() +
					", Task Completion Status = " + newTaskCompletionStatus.toString());
			smr.setResourceState(newResourceState);
			smr.setTaskCompletionStatus(newTaskCompletionStatus);
			persistResource(smr);
		} else
			LOG.debug("Mesos Task Status Update was not mapped to a Resource State, no action");	
	}
	
	
	
	// Handle the Status updates from Mesos
	public void taskStatusUpdate(TaskStatus taskStatus) {
		LOG.debug("!!!!!!!!!!!! MESOS TASK STATUS UPDATE start !!!!!!!!!!!!!!!");
		
		StreamsMesosResource.ResourceState oldResourceState, newResourceState;
		StreamsMesosResource.RequestState requestState;
		
		String taskId = taskStatus.getTaskId().getValue();
		String taskSlaveIP = "Unavailable";
		try {
			taskSlaveIP = taskStatus.getContainerStatus().getNetworkInfos(0).getIpAddresses(0).getIpAddress();
		} catch (Exception e) {}
		
		StreamsMesosResource smr = getResourceByTaskId(taskId);

		if (smr != null) {
			oldResourceState = smr.getResourceState();
			LOG.trace("oldResourceState: " + oldResourceState.toString());
			mapAndUpdateMesosTaskStateToResourceState(smr, taskStatus.getState());
			newResourceState = smr.getResourceState();
			LOG.trace("newResourceState: " + newResourceState.toString());
			requestState = smr.getRequestState();
			
			
			boolean changed = (oldResourceState != newResourceState) ? true:false;
			
			if (changed) {
				LOG.debug("Resource state of " + smr.getId() + " changed from " + oldResourceState + " to " + newResourceState + " and Request state is " + requestState);
			
			
				// LAUNCHED ?? Anything to do?
				
				// RUNNING
				if (newResourceState == ResourceState.RUNNING && requestState == RequestState.NEW) {
					LOG.debug("Resource " + smr.getId() + " is now RUNNING and RequestState was NEW, no action required");
					//Allocated within time so no need to notify client
					// Let the StreamsMesosResourceManager wait loop set it to allocated when it sees it RUNNING
					//smr.setRequestState(RequestState.ALLOCATED);
				} else if (newResourceState == ResourceState.RUNNING && requestState == RequestState.PENDING) {
					LOG.debug("Resource " + smr.getId() + " is now RUNNING and RequestState was PENDING, changing RequestState to ALLOCATED and notifying Streams");
					// Need to notify client
					smr.setRequestState(RequestState.ALLOCATED);
					persistResource(smr);
					smr.notifyClientAllocated();
				} else if (newResourceState == ResourceState.RUNNING && oldResourceState == ResourceState.STOPPING) {
					LOG.debug("Resource " + smr.getId() + " is now RUNNING, but it was STOPPING.  Issuing stop again.");
					smr.stop();
					persistResource(smr);
					
				// STOPPED and FAILED - may need to better test to determine normal expected from unexpected
					
				// STOPPED - normal
				} else if (newResourceState == ResourceState.STOPPED) {
					LOG.info("Resource " + smr.getId() + " task id " + taskId + " has stopped");
					LOG.debug("  changing RequestState to RELEASED.");
					smr.setRequestState(RequestState.RELEASED);
					persistResource(smr);
	
				// FAILED - abnormal
				// This may be where we need to notify streams something bad happened
				} else if (newResourceState == ResourceState.FAILED) {
					// If the Failure occurred before we notified Streams, then we can just let it die and put back on newRequestList
					// This is a case when a slave has issues
					LOG.warn("Resource " + smr.getId() + " with Mesos task Id " + taskId + " Failed on Mesos slave: " + taskSlaveIP);
					LOG.warn("  Message: " + taskStatus.getMessage());
					LOG.debug("taskStatus details: " + taskStatus);
					if (requestState == RequestState.NEW) {
						LOG.warn("Resource " + smr.getId() + " request state is NEW, queuing to relaunch the task");
						reLaunchResourceTask(smr);
					} else if (requestState == RequestState.ALLOCATED) {
						LOG.warn("Resource " + smr.getId() + " Failed with request state ALLOCATED, notify Streams of revoke");
						smr.setRequestState(RequestState.RELEASED);
						persistResource(smr);
						smr.notifyClientRevoked();
					} else if (requestState == RequestState.PENDING) {
						LOG.warn("Resource " + smr.getId() + " Failed with request state PENDING, notify Streams of revoke");
						smr.setRequestState(RequestState.RELEASED);
						persistResource(smr);
						smr.notifyClientRevoked();		
					} else if (requestState == RequestState.CANCELLED || requestState == RequestState.RELEASED) {
						LOG.warn("Resource " + smr.getId() + " Failed with requestState " + requestState + ", no action required, but not sure why task was still running to have a status update");
					}
				} else {
					LOG.debug("Change in task status update did not require any action.");
				}
			}

		} else {
			LOG.warn("taskStatusUpdate from state failed to find resource (TaskID: " + taskId + ")");
			return;
		}
		LOG.debug("!!!!!!!!!!!! MESOS TASK STATUS UPDATE stop !!!!!!!!!!!!!!!");

	}
	
	// Handle cancelling a resource request
	public void cancelPendingResource(ResourceDescriptor descriptor) throws ResourceManagerException {
		LOG.debug("CancelPendingResource: " + descriptor);
		StreamsMesosResource smr = getResource(getResourceId(descriptor));
		if (smr != null) {
			// So what state is it in?  What if we just allocated it?
			// Update its request state
			smr.setRequestState(RequestState.CANCELLED);
			persistResource(smr);
			// Need to cancel if running or launched
			if (smr.isRunning() || smr.isLaunched()) {
				// Its running so no need to remove from list of requsted resources, but need to stop it
				LOG.debug("Pending Resource (" + getResourceId(descriptor) + ") cancelled by streams, but it is already launced or "
						+ "running, stopping...");
				smr.stop();
				persistResource(smr);
			} else {
				// Remove from the requested resource list if it is on it
				removeRequestedResource(smr);
				// If it was new, set it to CANCELLED so we no it was never started
				if (smr.getResourceState() == ResourceState.NEW) {
					smr.setResourceState(ResourceState.CANCELLED);
					persistResource(smr);
				}
			}
		} else {
			throw new ResourceManagerException("CancelPendingResource failed to find resource (id: " + getResourceId(descriptor) + ")");
		}
	}
	
	
	// Release a single resource
	public void releaseResource(ResourceDescriptor descriptor) throws ResourceManagerException {
		StreamsMesosResource smr = getResource(getResourceId(descriptor));
		if (smr != null) {
			smr.stop();
			persistResource(smr);
		} else {
			LOG.info("releaseResource: Resource no longer exists (id: " + getResourceId(descriptor) +
					"), nothing to do.");
		}
	}
	
	
	// Release all resources for a given client
	// !!! NOTE: need to handle multiple clients in the future
	public void releaseAllResources(ClientInfo client) throws ResourceManagerException {
		// Verify we are working with this client
		Properties clientProps = getClientInfo(client.getClientId());
		if (clientProps != null) {
			for (StreamsMesosResource smr : getAllResources().values()) {
				smr.stop();
				persistResource(smr);
			}
		} else {
			LOG.info("releaseAllResources: Unknown client (" + client.getClientId() + 
					"), nothing to do.");
		}
	}
	
	
	
	////////////////////////////////////////////////////////
	/// Client Information Serialization and Persistence
	////////////////////////////////////////////////////////
	
	// NOTE: ClientInfo is an interface exposed by IBM Resource Manager API
	//       ClientINformation is their concrete class, however it is not exposed
	//       We will store properties of ClientInfo in persistence and use it
	//       for any comparisons required to identify new connections
	
    public Properties getClientInfoProperties(ClientInfo ci) {
        Properties props = new Properties();
        props.setProperty("clientId", ci.getClientId());
        props.setProperty("clientName", ci.getClientName());
        props.setProperty("domainId", ci.getDomainId());
        props.setProperty("installPath", ci.getInstallPath());
        props.setProperty("installVersion", ci.getInstallVersion());
        props.setProperty("osMajorVersion", Integer.toString(ci.getOSMajorVersion()));
        props.setProperty("osMinorVersion", Integer.toString(ci.getOSMinorVersion()));     
        props.setProperty("zkConnect", ci.getZkConnect());
        props.setProperty("isController", ci.isController() ? "true" : "false");
        props.setProperty("isStreamtool", ci.isStreamtool() ? "true" : "false");
        props.setProperty("os", ci.getOS().name());
        props.setProperty("architecture", ci.getArchitecture().name());

        return props;
    } 
	
	
    /**
     * Returns list of all known client identifiers
     * 
     * @return Collection<String>
     */
    public Collection<String> getClientIds() {
        return getChildren(getClientsPath());
        // Pre-persistence 
    	//List<String> clientIds = new ArrayList<String>();
    	//clientIds.add(_clientInfo.getClientId());
    	
    	//return clientIds;
    }
    
    /**
     * Returns client information properties
     * Because we cannot store/create a true ClientInfo object
     */

    /**
     * Returns client information
     * 
     * @param clientId - String
     * @return ClientInfo
     */
    public Properties getClientInfo(String clientId) {
        Properties clientProps = null;
        
        try {
        	clientProps = getPath(getClientPath(clientId));
        } catch (Throwable t) {
        	LOG.error("Persistence getClientInfo Error: " + t.getMessage());
        }
 
        if (clientProps == null) {
        	LOG.debug("getClientInfo: Client not found (" + clientId + ").  Possibly restarting a domain.");
        }

        return clientProps;
    }
    

    /**
     * Set client information in persistence
     * 
     * @param client - ClientInfo
     */
    public void setClientInfo(ClientInfo client) {
    	if (client == null) {
    		LOG.warn("setClientInfo on state failed because it was passed null client object");
    		return;
    	}
    	
    	// TEMPORARY TO ONLY HANDLE ONE CLIENT
	    if (_clientInfo != null) {
	    	if (!(_clientInfo.getClientId().equals(client.getClientId()))) {	    	
	        	LOG.warn("setClientInfo on state failed because clientId(" + client.getClientId() + ") does not match the client id already set (" + _clientInfo.getClientId() + ").  Must be restarting a domain");
	        	return;
	    	}
	    }
	    
	    // Same one or new
	    _clientInfo = client;
	    
	    // CORRECT CODE FOR HANDLINE MULTIPLE
	    try {
	    	String clientId = client.getClientId();
	    	
	    	// <resmgr>/clients/<clientId>
	    	String clientPath = getClientPath(clientId);
	    	if (_persistence.exists(clientPath)) {
	    		// Update it
	    		setPath(clientPath,getClientInfoProperties(client));
	    	}
	    } catch (Throwable t) {
        	LOG.error("Persistence setClientInfo Error: " + t.getMessage());
	    }

    }
    /**
     * Creates client info and creates index for resources it has requested
     * @param client
     * @param resourceId
     */
    public void addClientInfo(ClientInfo client, String resourceId) {
    	// Update Client info if necessary
    	setClientInfo(client);
    	    	
    	try {
    		String clientId = client.getClientId();
    		
    		// <resmgr>/clients/<clientId>/<resourceId>
    		Properties props = new Properties();
    		props.setProperty("client", clientId);
    		setPath(getClientResourcePath(clientId,resourceId),props);
    	} catch (Throwable t) {
        	LOG.error("Persistence addClientInfo Error: " + t.getMessage());
	    }
    }
    
    
    
    //////////////////////////////////////////
    // PERSISTENCE OF STATE
    //////////////////////////////////////////
    
    public String getMesosFrameworkId() {
    	String frameworkId = null;
    	Properties props = getPath("mesosFrameworkId");
    	if (props != null) {
    		frameworkId = props.getProperty("mesosFrameworkId");
    	}
    	return frameworkId;
    }
    
    public void setMesosFrameworkId(String mesosFrameworkId) {
    	Properties props = new Properties();
    	props.setProperty("mesosFrameworkId", mesosFrameworkId);
    	setPath("mesosFrameworkId",props);
    	
    }
    
    /**
     * Save StreamsMesosResource to persistent storage
     * @param smr - StreamsMesosResource object to save/update
     */
    private void persistResource(StreamsMesosResource smr) {
    	String resourceId = smr.getId();
    	try {
	    	LOG.debug("Persisting Resource: resourceId = " + smr.getId());
	    	
	    	// persist: resources/<resourceId>
	    	String resourcePath = getResourcePath(resourceId);
	    	if (!_persistence.exists(resourcePath)) {
	    		LOG.trace("  Resource did not exist in persistence (" + resourcePath + ") yet");
	    	}
	    	LOG.trace("  setPath: path=" + resourcePath + ", Resource properties=" + smr.getProperties());
	    	setPath(resourcePath,smr.getProperties());
    	} catch (Throwable t) {
    		LOG.error("Failed to persist smr with resourceID(" + resourceId + "): " + t.getMessage() + "\n" + t);
    		t.printStackTrace();
    	}
    }
    
    private StreamsMesosResource retrieveResource(String resourceId) {
    	StreamsMesosResource smr = null;
    	
    	Properties props = getPath(getResourcePath(resourceId));
    	
    	if (props != null) {
    		smr = new StreamsMesosResource(_manager, props);
    	}
    	
    	return smr;
    }
    
    private void persistResourceRequest(String resourceId) {
		try {
			// persist: requestedResources/<resourceId>
			setPath(getRequestedResourcePath(resourceId),null);
		} catch (Throwable t) {
    		LOG.error("Failed to persist requestedResource Index entry(" + resourceId + "): " + t.getMessage());				
		}    	
    }
    
    private void deleteResourceRequest(String resourceId) {
    	if (resourceId != null) {
    		deletePath(getRequestedResourcePath(resourceId));
    	}
    }
    
    //////////////////////////////////////////
    /// PERSISTENCE MODEL HELPERS
    //////////////////////////////////////////
    
    private String getResourcesPath() {
    	return "resources";
    }
    
    private String getResourcePath(String resourceId) {
    	return getResourcesPath() + "/" + resourceId;
    }
    
    private String getRequestedResourcesPath() {
    	return "requestedResources";
    }
    
    private String getRequestedResourcePath(String resourceId) {
    	return getRequestedResourcesPath() + "/" + resourceId;
    }
    
    private String getClientsPath() {
    	return "clients";
    }
    
    private String getClientPath(String clientId) {
    	return getClientsPath() + "/" + clientId;
    }
    
    private String getClientResourcePath(String clientId, String resourceId) {
    	return getClientPath(clientId) + "/" + resourceId;
    }
    
    //////////////////////////////////////////
    /// PERSISTENCE INTERFACES
    //////////////////////////////////////////
    
    /**
     * Returns properties object from path; null if path does not exist
     */
    private Properties getPath(String path) {
    	Properties props = null;
    	
    	try {
    		if (_persistence.exists(path)) {
    			byte[] data = _persistence.get(path);
    			props = _persistence.toProperties(data);
    		}
    	} catch (Throwable t) {
    		LOG.error("Persistence getPath Error: " + t.getMessage());
    	}
    	
    	return props;
    }
    
    /**
     * Returns requested property from path; null if property does not exist
     */
    private String getPathProperty(String path, String propName) {
    	String propValue = null;
    	Properties props = getPath(path);
    	if (props != null) {
    		propValue = props.getProperty(propName);
    	}
    	return propValue;
    }
    
    /**
     * Sets persistence path properties (saves into zookeeper)
     */
    private void setPath(String path, Properties props) {
    	try {
    		_persistence.set(path,  (props != null ? _persistence.toBytes(props) : null));
    	} catch (Throwable t) {
    		LOG.error("Persistence setPath Error: " + t.getMessage());
    	}
    }
    
    /**
     * Delete persistence path properties
     */
    private void deletePath(String path) {
    	try {
    		_persistence.delete(path);
    	} catch (Throwable t) {
    		LOG.error("Persistence deletePath Error: " + t.getMessage());
    	}
    }
    
    /**
     * Get children list for a path
     */
	private Collection<String> getChildren(String path) {
		try {
			if (_persistence.exists(path)) {
				return _persistence.getChildren(path);
			}
		} catch (Throwable t) {
			LOG.error("Persistence getChildren(" + path + ") error: " + t.getMessage());
		}
		
		return new ArrayList<String>(0);
	}
}
