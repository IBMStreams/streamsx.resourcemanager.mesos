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
/**
 * Wraps a resource request from IBM Streams Resource Server
 *
 */

//import java.net.UnknownHostException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.Resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.streams.resourcemgr.ClientInfo;
import com.ibm.streams.resourcemgr.ResourceDescriptor;
import com.ibm.streams.resourcemgr.ResourceDescriptor.ResourceKind;
import com.ibm.streams.resourcemgr.ResourceDescriptorState;
import com.ibm.streams.resourcemgr.ResourceDescriptorState.AllocateState;
import com.ibm.streams.resourcemgr.ResourceManagerException;
import com.ibm.streams.resourcemgr.ResourceManagerUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import com.ibm.streams.resourcemgr.yarn.ContainerWrapper.ContainerWrapperState;

class StreamsMesosResource {
	private static final Logger LOG = LoggerFactory.getLogger(StreamsMesosResource.class);

	// Represents interpretation of Mesos Task
	public static enum ResourceState {
		NEW(0), LAUNCHED(1), RUNNING(2), STOPPING(3), STOPPED(4), FAILED(5), CANCELLED(6);

		ResourceState(int state) {}
		
		public static ResourceState fromState(int state) {
			return values()[state];
		}
	}
	
	// Represents standing with IBM Streams for this request
	public static enum RequestState {
		NEW(0), ALLOCATED(1), PENDING(2), CANCELLED(3), RELEASED(4);
		
		RequestState(int state) {}
		
		public static RequestState fromState(int state) {
			return values()[state];
		}
	}
	
	// Represents completion status for the task
	public static enum TaskCompletionStatus {
		NONE(0), FINISHED(1), FAILED(2), ERROR(3), KILLED(4), LOST(5);
		
		TaskCompletionStatus(int state) {}
		
		public static TaskCompletionStatus fromState(int state) {
			return values()[state];
		}	
	}

	// PRIVATE MEMBERS require persisting
	private String _resourceId; // Unique Identifier
	private String _streamsDisplayName; // Streams Display Name
	private String _clientId; // Client ID that requested this resource
	private String _domainId = null;
	private String _zkConnect = null;
	private String _homeDir = null;
	private ResourceState _resourceState = ResourceState.NEW;
	private RequestState _requestState = RequestState.NEW;
	private TaskCompletionStatus _taskCompletionStatus = TaskCompletionStatus.NONE;
	private long _resourceStateChangeTime = System.currentTimeMillis();  // Time of last resourceState change
	private Set<String> _tags = new HashSet<String>();
	private double _memory = -1; // requested Memory
	private double _cpu = -1; // requested CPU
	private double _memoryAllocated = -1; // allocated Memory
	private double _cpuAllocated = -1; // allocated CPU
	private boolean _isMaster = false;
	private String _resourceType = null; // usually mesos, but is a variable
	private String _taskId = null;  // Mesos task ID
	private String _hostName = null; // Hostname task is running in
	
	// PRIVATE MEMBERS need to be re-acquired after de-serialized from persistence	
	// Resource manager
	private StreamsMesosResourceManager _manager; // Resource manager
	

	
	// Constructor for initial creation
	public StreamsMesosResource(String id, ClientInfo client, StreamsMesosResourceManager manager) {
		this._resourceId = id;
		this._resourceType = Utils.getProperty(manager.getConfig(), StreamsMesosConstants.PROPS_RESOURCE_TYPE);
		this._streamsDisplayName =  this._resourceType + "_" + id;
		this._clientId = client.getClientId();
		this._manager = manager;
		this._domainId = client.getDomainId();
		this._zkConnect = client.getZkConnect();
		this._homeDir = Utils.getProperty(manager.getConfig(), StreamsMesosConstants.PROPS_USER_HOME);
	}
	
	// Constructor from persisted Properties
    public StreamsMesosResource(StreamsMesosResourceManager manager, Properties props) {
        _manager = manager;
        
        _resourceId = props.getProperty("resourceId");
        _streamsDisplayName = props.getProperty("streamsDisplayName");
        _clientId = props.getProperty("clientId");
        _domainId = props.getProperty("domainId");
        _zkConnect = props.getProperty("zkConnect");
        _homeDir = props.getProperty("homeDir");
        _resourceState = ResourceState.valueOf(props.getProperty("resourceState"));
        _requestState = RequestState.valueOf(props.getProperty("requestState"));
        _taskCompletionStatus = TaskCompletionStatus.valueOf(props.getProperty("taskCompletionStatus"));
        _resourceStateChangeTime = Long.parseLong(props.getProperty("resourceStateChangeTime"));
        _tags = (Set<String>)Utils.fromCsv(props.getProperty("tags"));
        _memory = Double.parseDouble(props.getProperty("memory"));
        _cpu = Double.parseDouble(props.getProperty("cpu"));
        _memoryAllocated = Double.parseDouble(props.getProperty("memoryAllocated"));
        _cpuAllocated = Double.parseDouble(props.getProperty("cpuAllocated"));
        _isMaster = props.getProperty("isMaster").equals("true");
        _resourceType = props.getProperty("resourceType");
        _taskId = props.getProperty("taskId");
        _hostName = props.getProperty("hostName");
    }	
    
    
	/**
     * Returns request properties used to re-construct
     * 
     * @return Properties
     */
    public Properties getProperties() {
        Properties props = new Properties();
        props.setProperty("resourceId", _resourceId);
        props.setProperty("streamsDisplayName", _streamsDisplayName);
        props.setProperty("clientId", _clientId);
        props.setProperty("domainId", _domainId);
        props.setProperty("zkConnect", _zkConnect);
        props.setProperty("homeDir",  _homeDir);
        props.setProperty("resourceState", _resourceState.name());
        props.setProperty("requestState", _requestState.name());
        props.setProperty("taskCompletionStatus", _taskCompletionStatus.name());
        props.setProperty("resourceStateChangeTime", Long.toString(_resourceStateChangeTime));
        props.setProperty("tags", Utils.toCsv(_tags));
        props.setProperty("memory", Double.toString(_memory));
        props.setProperty("cpu", Double.toString(_cpu));
        props.setProperty("memoryAllocated", Double.toString(_memoryAllocated));
        props.setProperty("cpuAllocated", Double.toString(_cpuAllocated));
        props.setProperty("isMaster", _isMaster ? "true" : "false");
        props.setProperty("resourceType", _resourceType);
        props.setProperty("taskId", _taskId);
        props.setProperty("hostName", _hostName);

        return props;
    }
	
 
	public String getId() {
		return _resourceId;
	}

	public String getDomainId() {
		return _domainId;
	}

	public String getZkConnect() {
		return _zkConnect;
	}

	public boolean isMaster() {
		return _isMaster;
	}

	public void setMaster(boolean isMaster) {
		this._isMaster = isMaster;
	}

	public double getMemory() {
		return _memory;
	}

	public void setMemory(double memory) {
		this._memory = memory;
	}

	public double getCpu() {
		return _cpu;
	}

	public void setCpu(double cpu) {
		this._cpu = cpu;
	}

	public Set<String> getTags() {
		return _tags;
	}
	
	
	
	/**
	 * @return the homeDir
	 */
	public String getHomeDir() {
		return _homeDir;
	}

	/**
	 * @param homeDir the homeDir to set
	 */
	public void setHomeDir(String homeDir) {
		this._homeDir = homeDir;
	}

	/**
	 * @return the state
	 */
	public ResourceState getResourceState() {
		return _resourceState;
	}

	/**
	 * @param state the state to set
	 */
	public void setResourceState(ResourceState state) {
		this._resourceState = state;
		this._resourceStateChangeTime = System.currentTimeMillis();
	}
	
	public long getResourceStateDurationSeconds() {
		return ((System.currentTimeMillis() - this._resourceStateChangeTime) / 1000);
	}
	
	
	

	public RequestState getRequestState() {
		return _requestState;
	}

	public void setRequestState(RequestState _requestState) {
		this._requestState = _requestState;
	}

	public TaskCompletionStatus getTaskCompletionStatus() {
		return _taskCompletionStatus;
	}

	public void setTaskCompletionStatus(TaskCompletionStatus _taskCompletionStatus) {
		this._taskCompletionStatus = _taskCompletionStatus;
	}

	/**
	 * @return the taskId
	 */
	public String getTaskId() {
		return _taskId;
	}

	/**
	 * @param taskId the taskId to set
	 */
	public void setTaskId(String taskId) {
		this._taskId = taskId;
	}
	
	public String getHostName() {
		return _hostName;
	}

	public void setHostName(String hostName) {
		this._hostName = hostName;
	}

	public boolean isLaunched() {
		return _resourceState == ResourceState.LAUNCHED;
	}
	
	public boolean isRunning() {
		return _resourceState == ResourceState.RUNNING;
	}
	
	public boolean isAllocated() {
		return _requestState == RequestState.ALLOCATED;
	}

	
	
	public ResourceDescriptor getDescriptor() {
		return new ResourceDescriptor(_resourceType, ResourceKind.CONTAINER, _resourceId,
				_streamsDisplayName, _hostName);

	}

	public ResourceDescriptorState getDescriptorState() {
		AllocateState s = isRunning() ? AllocateState.ALLOCATED : AllocateState.PENDING;
		return new ResourceDescriptorState(s, getDescriptor());
	}
	
	

	/* Mesos Helper Methods */
	/*
	 * Create the CommandInfo for initiating a Streams Resource Question: May
	 * need a second version for differences between master streams resource
	 * (when domain is started) and all others
	 */
	public CommandInfo buildStreamsResourceStartupCommand() {

		CommandInfo.Builder cmdInfoBuilder = Protos.CommandInfo.newBuilder();

		// Create command, depends on deployment mechanism
		StringBuffer cmdBuffer = new StringBuffer();

		cmdBuffer.append("echo 'Setting up Streams Environment'");
		if (getHomeDir() != null)
			//cmdBuffer.append(";export HOME=${MESOS_SANDBOX}");
			cmdBuffer.append("; export HOME=" + getHomeDir());

		if (Utils.getBooleanProperty(_manager.getConfig(), StreamsMesosConstants.PROPS_DEPLOY)) {
			// run the streams resource installer
			// create softlink for StreamsLink
			cmdBuffer.append(";./StreamsResourceInstall/streamsresourcesetup.sh");
			cmdBuffer.append(" --install-dir ./InfoSphereStreams");
			cmdBuffer.append(" &> streamsinstall.out || exit 1");
			cmdBuffer.append(";cat ./InfoSphereStreams/.streams.version.dir | ");
			cmdBuffer.append(" xargs -I '{}' ln -s './InfoSphereStreams/{}' StreamsLink");
		} else {
			// if --deploy not set, we assume streams is installed on all
			// machines
			String streamsInstall = Utils.getProperty(_manager.getConfig(), StreamsMesosConstants.PROPS_STREAMS_INSTALL_PATH);
			cmdBuffer.append(";ln -s " + streamsInstall + " StreamsLink");
		}
		// Source streams install path
		cmdBuffer.append(";source StreamsLink/bin/streamsprofile.sh");
		cmdBuffer.append("; env > env.out");
		// Verify Streamtool version
		cmdBuffer.append(";streamtool version");
		// Start Streams Controller
		cmdBuffer.append(";" + ResourceManagerUtilities.getStartControllerCommand("$PWD/StreamsLink", getZkConnect(),
				getDomainId(), getDescriptor(), getTags(), false, isMaster()));
		cmdBuffer.append(" &> application.log");
		// Set command string
		LOG.debug("Task Command: " + cmdBuffer.toString());
		cmdInfoBuilder.setValue(cmdBuffer.toString());

		// Add URI's (if any)
		cmdInfoBuilder.addAllUris(_manager.getUriList());

		return cmdInfoBuilder.build();
	}

	/**
	 * Create Mesos Task Info for this Resource If cpus and/or memory are not
	 * specified, use all from offer
	 */
	public Protos.TaskInfo buildStreamsMesosResourceTask(Protos.Offer offer) {

		double requestedCpu = getCpu();
		double requestedMemory = getMemory();
		
		LOG.trace("building Mesos Task: cores = " + requestedCpu + ", memory=" + requestedMemory);

		// Create taskId
		Protos.TaskID taskIdProto = generateTaskId(getId());
		
		// Set Host it will be run on
		setHostName(offer.getHostname());

		// Calculate resources usage if configured to use it all
		if ((requestedCpu == StreamsMesosConstants.USE_ALL_CORES)
				|| (requestedMemory == StreamsMesosConstants.USE_ALL_MEMORY)) {
			for (Resource r : offer.getResourcesList()) {
				if ((r.getName().equals("cpus")) && (requestedCpu == StreamsMesosConstants.USE_ALL_CORES)) {
					requestedCpu = r.getScalar().getValue();
					LOG.debug("SMR Task (" + getId() + ") using all cpus in offer: " + String.valueOf(requestedCpu));
				}
				if ((r.getName().equals("mem")) && (requestedMemory == StreamsMesosConstants.USE_ALL_MEMORY)) {
					requestedMemory = r.getScalar().getValue();
					LOG.debug("SMR Task (" + getId() + ") using all memory in offer: " + String.valueOf(requestedMemory));
				}
			}
		}
		
		// Set what we are going to allocate
		_cpuAllocated = requestedCpu;
		_memoryAllocated = requestedMemory;

		// Get the commandInfo from the Streams Mesos Resource
		Protos.CommandInfo commandInfo = buildStreamsResourceStartupCommand();

		// Work on getting this fillout out correctly
		Protos.TaskInfo task = Protos.TaskInfo.newBuilder().setName(getId()).setTaskId(taskIdProto)
				.setSlaveId(offer.getSlaveId()).addResources(buildResource("cpus", requestedCpu))
				.addResources(buildResource("mem", requestedMemory))
				// .setData(ByteString.copyFromUtf8("" + taskIdCounter))
				.setCommand(Protos.CommandInfo.newBuilder(commandInfo)).build();

		return task;
	}

	private Protos.Resource buildResource(String name, double value) {
		return Protos.Resource.newBuilder().setName(name).setType(Protos.Value.Type.SCALAR)
				.setScalar(buildScalar(value)).build();
		// Test with role
		//return Protos.Resource.newBuilder().setName(name).setType(Protos.Value.Type.SCALAR)
		//		.setScalar(buildScalar(value)).setRole("streams").build();
	}

	private Protos.Value.Scalar.Builder buildScalar(double value) {
		return Protos.Value.Scalar.newBuilder().setValue(value);
	}
	
	// Create the Mesos task ID based on the resource ID
	// Example: resource_5 => streams_resource_5_0
	private Protos.TaskID generateTaskId(String resourceId) {
		String taskid = Utils.generateNextId(StreamsMesosConstants.MESOS_TASK_ID_PREFIX + resourceId);
		setTaskId(taskid);

		return Protos.TaskID.newBuilder().setValue(taskid).build(); 
	}
	
	public void stop() {
		LOG.debug("*** Requested to Stop Resource: " + _resourceId + ", resource State: " + _resourceState);
		
		switch(_resourceState) {
		case NEW:
		//case STOPPING:
		case STOPPED:
		case FAILED:
			return; // nothing to do
		default:
			break;
		}
		
		LOG.info("Stopping Streams controller and thus Mesos task: " + _resourceId);
		setResourceState(ResourceState.STOPPING);
		try {
			ResourceDescriptor rd = getDescriptor();
			LOG.debug("Stopping Domain Controller: " + rd);
			boolean clean = false;
			//LOG.info("stopControllerCmd: " + ResourceManagerUtilities.getStopControllerCommandElements(
			//		_argsMap.get(StreamsMesosConstants.INSTALL_PATH_ARG), getZkConnect(), getDomainId(), rd, clean));
			ResourceManagerUtilities.stopController(getZkConnect(), getDomainId(), rd, clean);
		} catch (ResourceManagerException rme) {
			LOG.warn("Error shutting down resource streams controller: " + this, rme);
			rme.printStackTrace();
		}

		// No need to stop anything directly in Mesos since we used the Mesos CommandExecutor
		// Once the controller exits, the task should exit and status update will be reported
		// to the scheduler.
		
		// It is there that we will be notified that the task has stopped and will update the
		// _resourceState to STOPPED

	}
	
	
	public void notifyClientAllocated() {
		Collection<ResourceDescriptor> descriptors = new ArrayList<ResourceDescriptor>();
		descriptors.add(getDescriptor());
		LOG.info("Sending resourcesAllocated notification to Streams for resource: " + getId());
		_manager.getResourceNotificationManager().resourcesAllocated(_clientId, descriptors);
	}
	
	public void notifyClientRevoked() {
		Collection<ResourceDescriptor> descriptors = new ArrayList<ResourceDescriptor>();
		descriptors.add(getDescriptor());
		LOG.info("Sending resourcesRevoked notification to Streams for resource: " + getId());
		_manager.getResourceNotificationManager().resourcesRevoked(_clientId, descriptors);
	}

	@Override
	public String toString() {
		return "StreamsMesosResource [resourceId=" + _resourceId + 
				", resourceState=" + _resourceState + 
				", requestState=" + _requestState +
				", domainId=" + _domainId + 
				", zkConnect=" + _zkConnect + 
				", memoryRequested=" + _memory + 
				", memoryAllocated=" + _memoryAllocated +
				", cpuRequested=" + _cpu + 
				", cpuAllocated=" + _cpuAllocated +
				", isMaster=" + _isMaster + 
				", tags=" + _tags + 
				", taskId=" + _taskId + 
				", hostName=" + _hostName +
				", taskCompletionStatus=" + _taskCompletionStatus +
				"]";
	}
	
//	public JSONObject toJsonObject(boolean longVersion) {
//		JSONObject resource = new JSONObject();
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_ID, getId());
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_STREAMS_ID, _streamsDisplayName);
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TASK_ID, _taskId);
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_RESOURCE_STATE, _resourceState.toString());
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_REQUEST_STATE, _requestState.toString());
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_COMPLETION_STATUS, _taskCompletionStatus.toString());
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_HOST_NAME, _hostName);
//		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_IS_MASTER, _isMaster);
//		
//		if (longVersion) {
//			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TAGS, _tags.toString());
//			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_CORES, String.valueOf(_cpuAllocated));
//			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_MEMORY, String.valueOf(_memoryAllocated));
//		}
//
//		return resource;
//	}
	
	public ObjectNode resourceStateAsJsonObjectNode(boolean longVersion) {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode resource = mapper.createObjectNode();
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_ID, getId());
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_STREAMS_ID, _streamsDisplayName);
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TASK_ID, _taskId);
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_RESOURCE_STATE, _resourceState.toString());
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_REQUEST_STATE, _requestState.toString());
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_COMPLETION_STATUS, _taskCompletionStatus.toString());
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_HOST_NAME, _hostName);
		resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_IS_MASTER, _isMaster);
	
		if (longVersion) {
			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_TAGS, _tags.toString());
			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_CORES, String.valueOf(_cpuAllocated));
			resource.put(StreamsMesosConstants.CUSTOM_RESULT_RESOURCE_MEMORY, String.valueOf(_memoryAllocated));
		}
	
		return resource;
	}
	
	public String toJson() {
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.writeValueAsString(this);
		} catch (Exception e) {
			LOG.error("Error creating JSON String of resource " + this.getId() + ": " + e.getMessage());
			return null;
		}
	}
}
