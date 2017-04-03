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
		
		
		// Add to collections to track
		LOG.debug("Queuing new Resource Request: " + smr.toString());
		
		_allResources.put(smr.getId(), smr);
		requestResource(smr);
		
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
	// Example is a problem with mesos slave that prevents controller from running
	private void reLaunchResourceTask(StreamsMesosResource smr) {
		LOG.debug("Re-launching resource task" + smr.getId());
		smr.setResourceState(ResourceState.NEW);
		smr.setTaskCompletionStatus(null);
		smr.setTaskId(null);
		requestResource(smr);
	}
	
	private void requestResource(StreamsMesosResource smr) {
		synchronized(this) {
			_requestedResources.add(smr);
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
	public void removeRequestedResource(StreamsMesosResource smr) {
		_requestedResources.remove(smr);
	}
	
	// Remove a collection of requested resources
	public void removeRequestedResources(Collection<StreamsMesosResource> resources) {
		_requestedResources.removeAll(resources);
	}
	
	// Return resourceId
	// placeholder for when we implement persistence storage in paths
	public String getResourceId(String nativeResourceId) {
		return nativeResourceId;
	}
	
	// Return resourceId associated with the resource descriptor from Streams
	public String getResourceId(ResourceDescriptor descriptor) {
		return getResourceId(descriptor.getNativeResourceId());
	}
	
	public StreamsMesosResource getResource(String resourceId) {
		if (_allResources.containsKey(resourceId)) {
			return _allResources.get(resourceId);
		} else {
			LOG.warn("getResource from state failed: Resource Not found (id: " + resourceId  + ")");
			return null;
		}
	}
	
	public StreamsMesosResource getResourceByTaskId(String taskId) {
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
	
	
	public void taskLaunched(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setResourceState(StreamsMesosResource.ResourceState.LAUNCHED);
			smr.setTaskCompletionStatus(StreamsMesosResource.TaskCompletionStatus.NONE);
		} else {
			LOG.warn("taskLaunched from state failed to find resource (id: " + resourceId + ")");
		}

	}
	
	public void setAllocated(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setRequestState(StreamsMesosResource.RequestState.ALLOCATED);
		} else {
			LOG.warn("setAllocated from state failed to find resource (id: " + resourceId + ")");
		}		
	}
	
	public void setPending(String resourceId) {
		StreamsMesosResource smr = getResource(resourceId);
		if (smr != null) {
			smr.setRequestState(StreamsMesosResource.RequestState.PENDING);
		} else {
			LOG.warn("setPending from state failed to find resource (id: " + resourceId + ")");
		}
	}
	
	
	
	
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
					smr.notifyClientAllocated();
				} else if (newResourceState == ResourceState.RUNNING && oldResourceState == ResourceState.STOPPING) {
					LOG.debug("Resource " + smr.getId() + " is now RUNNING, but it was STOPPING.  Issuing stop again.");
					smr.stop();
					
				// STOPPED and FAILED - may need to better test to determine normal expected from unexpected
					
				// STOPPED - normal
				} else if (newResourceState == ResourceState.STOPPED) {
					LOG.info("Resource " + smr.getId() + " task id " + taskId + " has stopped");
					LOG.debug("  changing RequestState to RELEASED.");
					smr.setRequestState(RequestState.RELEASED);
	
				// FAILED - abnormal
				// This may be where we need to notify streams something bad happened
				} else if (newResourceState == ResourceState.FAILED) {
					// If the Failure occured before we notified Streams, then we can just let it die and put back on newRequestList
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
						smr.notifyClientRevoked();
					} else if (requestState == RequestState.PENDING) {
						LOG.warn("Resource " + smr.getId() + " Failed with request state PENDING, notify Streams of revoke");
						smr.setRequestState(RequestState.RELEASED);
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
			// Need to cancel if running or launched
			if (smr.isRunning() || smr.isLaunched()) {
				// Its running so no need to remove from list of requsted resources, but need to stop it
				LOG.debug("Pending Resource (" + getResourceId(descriptor) + ") cancelled by streams, but it is already launced or "
						+ "running, stopping...");
				smr.stop();
			} else {
				// Remove from the requested resource list if it is on it
				removeRequestedResource(smr);
				// If it was new, set it to CANCELLED so we no it was never started
				if (smr.getResourceState() == ResourceState.NEW) {
					smr.setResourceState(ResourceState.CANCELLED);
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
		} else {
			LOG.info("releaseResource: Resource no longer exists (id: " + getResourceId(descriptor) +
					"), nothing to do.");
		}
	}
	
	
	// Release all resources for a given client
	// !!! NOTE: need to handle multiple clients in the future
	public void releaseAllResources(ClientInfo client) throws ResourceManagerException {
		// Verify we are working with this client
		ClientInfo clientInfo = getClientInfo(client.getClientId());
		if (clientInfo != null) {
			for (StreamsMesosResource smr : getAllResources().values()) {
				smr.stop();
			}
		} else {
			LOG.info("releaseAllResources: Unknown client (" + client.getClientId() + 
					"), nothing to do.");
		}
	}
	
	
	
    /**
     * Returns list of all known client identifiers
     * 
     * @return Collection<String>
     */
    public Collection<String> getClientIds() {
        //return getChildren(getClientsPath());
    	List<String> clientIds = new ArrayList<String>();
    	clientIds.add(_clientInfo.getClientId());
    	
    	return clientIds;
    }

    /**
     * Returns client information
     * 
     * @param clientId - String
     * @return ClientInfo
     */
    public ClientInfo getClientInfo(String clientId) {
        ClientInfo client = null;
        
        // Just ensure we are on the same page with the clientId requested
        if (_clientInfo != null) {
        	if (_clientInfo.getClientId().equals(clientId)) {
        		client = _clientInfo;
        	} else {
        		LOG.warn("getClientInfo from state failed because clientId(" + clientId + ") does not match the client id we are working with (" + _clientInfo.getClientId() + ").  Must be restarting a domain.");
        	}
        }

        return client;
    }
    

    /**
     * Set client information to persistence
     * 
     * @param client - ClientInfo
     */
    public void setClientInfo(ClientInfo client) {
    	if (client != null) {
	        if (_clientInfo == null) {
	        	_clientInfo = client;
	        } else if (!(_clientInfo.getClientId().equals(client.getClientId()))) {
	        	LOG.warn("setClientInfo on state failed because clientId(" + client.getClientId() + ") does not match the client id already set (" + _clientInfo.getClientId() + ").  Must be restarting a domain");
	        } else {
	        	// Setting to the same value so no need to do anything
	        }
    	} else {
    		LOG.warn("setClientInfo on state failed because it was passed null client object");
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
     * Sets persistence path properties (saves into zookeeper)
     */
    private void setPath(String path, Properties props) {
    	try {
    		_persistence.set(path,  (props != null ? _persistence.toBytes(props) : null));
    	} catch (Throwable t) {
    		LOG.error("Persistence setPath Error: " + t.getMessage());
    	}
    }
	
}
