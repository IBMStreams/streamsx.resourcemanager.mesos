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

import java.io.FileReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.FrameworkInfo;

//import org.json.simple.parser.JSONParser;
//import org.json.simple.parser.ParseException;
//import org.json.simple.JSONObject;
//import org.json.simple.JSONArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.streams.resourcemgr.AllocateInfo;
import com.ibm.streams.resourcemgr.AllocateInfo.AllocateType;
import com.ibm.streams.resourcemgr.AllocateMasterInfo;
import com.ibm.streams.resourcemgr.ClientInfo;
import com.ibm.streams.resourcemgr.ResourceDescriptor;
import com.ibm.streams.resourcemgr.ResourceDescriptorState;
import com.ibm.streams.resourcemgr.ResourceManagerAdapter;
import com.ibm.streams.resourcemgr.ResourceManagerException;
import com.ibm.streams.resourcemgr.ResourceManagerUtilities;
import com.ibm.streams.resourcemgr.ResourceManagerUtilities.ResourceManagerPackageType;
import com.ibm.streams.resourcemgr.ResourceTagException;
import com.ibm.streams.resourcemgr.ResourceTags;
import com.ibm.streams.resourcemgr.ResourceTags.TagDefinitionType;

/**
 * @author bmwilli
 *
 */
public class StreamsMesosResourceManager extends ResourceManagerAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(StreamsMesosResourceManager.class);

	private StreamsMesosState _state;
	
	private Properties _config = null;

	private StreamsMesosScheduler _scheduler;
	private MesosSchedulerDriver _driver;
	private String _master;
	private List<Protos.CommandInfo.URI> _uriList = new ArrayList<Protos.CommandInfo.URI>();
	private boolean _deployStreams = false;
	private long _waitAllocatedSecs = StreamsMesosConstants.WAIT_ALLOCATED_SECS_DEFAULT;



	/*
	 * Constructor NOTE: Arguments passed are not in a reliable order need to
	 * identify flags you expect and others should be read as key,value
	 * sequential arguments. NOTE: These arguments come from the
	 * streams-on-mesos script and are passed through the StreamsResourceServer
	 * which it executes and which in turn constructs this class
	 * 
	 * Ultimate destination for configuration is _config Properties
	 * Precedence:
	 * Defaults->Environs->Properties->Arguments=> _config
	 */
	public StreamsMesosResourceManager(String[] args) throws ResourceManagerException, StreamsMesosException {
		LOG.debug("Constructing ResourceManagerAdapter: StreamsMesosResourceFramework");
		Map<String, String> envs = System.getenv();
		Map<String, String> argsMap = new HashMap<String, String>();
		Properties props = null;
		
		LOG.trace("Arguments: " + Arrays.toString(args));
		LOG.trace("Enironment: " + envs);

		argsMap.clear();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case StreamsMesosConstants.DEPLOY_ARG:
				argsMap.put(args[i], "true");
				break;
			case StreamsMesosConstants.MESOS_MASTER_ARG:
			case StreamsMesosConstants.ZK_ARG:
			case StreamsMesosConstants.TYPE_ARG:
			case StreamsMesosConstants.MANAGER_ARG:
			case StreamsMesosConstants.STREAMS_INSTALL_PATH_ARG:
			case StreamsMesosConstants.HOME_DIR_ARG:
			case StreamsMesosConstants.PROP_FILE_ARG:
			case StreamsMesosConstants.DOMAIN_ID_ARG:
				argsMap.put(args[i], args[++i]);
				break;
			}
		}
		LOG.trace("ArgsMap: " + argsMap);

		String propsFile = null;
		// Process Properties FileReader
		if (argsMap.containsKey(StreamsMesosConstants.PROP_FILE_ARG))
			propsFile = argsMap.get(StreamsMesosConstants.PROP_FILE_ARG);
		else
			propsFile = StreamsMesosConstants.RM_PROPERTIES_FILE;
		LOG.debug("Reading Properties file from: " + propsFile);
		props = new Properties();
		try {
			props.load(new FileReader(propsFile));
		} catch (FileNotFoundException e) {
			LOG.error("Could not find properties file: " + propsFile);
			throw new StreamsMesosException("Could not find properties file: " + propsFile, e);
		} catch (IOException e) {
			LOG.error("IO Error reading the properties file: " + propsFile);
			throw new StreamsMesosException("IO Error reading the properties file: " + propsFile, e);
		}

		LOG.trace("Properties: " + props.toString());
		
		// Use precedence of default->environs->props->arguments to build _config
		initializeConfig(envs, argsMap, props);
		
		LOG.debug("**************************************");
		LOG.debug("Configuration: " + _config.toString());
		LOG.debug("**************************************");
		
		// Set _waitAllocatedSecs 
		_waitAllocatedSecs = Utils.getLongProperty(_config, StreamsMesosConstants.PROPS_WAIT_ALLOCATED);
		_deployStreams = Utils.getBooleanProperty(_config, StreamsMesosConstants.PROPS_DEPLOY);

	}

	/**
	 * @return the _state
	 */
	public StreamsMesosState getState() {
		return _state;
	}
	
	/**
	 * @return the props
	 */
	public Properties getConfig() {
		return _config;
	}
	
	/**
	 * @return the _uriList
	 */
	public List<Protos.CommandInfo.URI> getUriList() {
		return _uriList;
	}

	////////////////////////////////
	/// Configuration Management
	////////////////////////////////
	private void initializeConfig(Map<String,String> envs, Map<String,String> argsMap, Properties props) throws ResourceManagerException {
		_config = new Properties();
		String propValue = null;
		
		// Mesos Master
		propValue = setConfigPropertyWithArg(StreamsMesosConstants.PROPS_MESOS_MASTER, props,
				StreamsMesosConstants.MESOS_MASTER_DEFAULT,
				StreamsMesosConstants.MESOS_MASTER_ARG, argsMap);
		
		// STREAMS_ZKCONNECT			
		setConfigPropertyWithEnv(StreamsMesosConstants.PROPS_ZK_CONNECT, props,
				StreamsMesosConstants.ENV_STREAMS_ZKCONNECT,
				StreamsMesosConstants.ZK_ARG, argsMap);
		if (propValue == null || propValue.equals("")) {
			throw new ResourceManagerException("Zookeeper not set.  Check env, props, arguments");
		}
		
		// Resource Manager Type
		propValue = setConfigPropertyWithArg(StreamsMesosConstants.PROPS_RESOURCE_TYPE, props,
				StreamsMesosConstants.RESOURCE_TYPE_DEFAULT,
				StreamsMesosConstants.TYPE_ARG, argsMap);
		
		// Streams Install Path for Resources
		propValue = setConfigPropertyWithEnv(StreamsMesosConstants.PROPS_STREAMS_INSTALL_PATH, props,
				StreamsMesosConstants.ENV_STREAMS_INSTALL,
				StreamsMesosConstants.STREAMS_INSTALL_PATH_ARG, argsMap);
		
		// Deploy Flag
		propValue = setConfigPropertyWithArg(StreamsMesosConstants.PROPS_DEPLOY, props, "false",
				StreamsMesosConstants.DEPLOY_ARG, argsMap);
		if (!Utils.getBooleanProperty(_config, StreamsMesosConstants.PROPS_DEPLOY) && 
				_config.getProperty(StreamsMesosConstants.PROPS_STREAMS_INSTALL_PATH) == null) {
			throw new ResourceManagerException("Streams intallation path for resource (env, arg, or prop) must be set when not deploying the Streams runtime.");
		}
		
		// Home Directory for user within resources
		propValue = setConfigPropertyWithEnv(StreamsMesosConstants.PROPS_USER_HOME, props,
				StreamsMesosConstants.ENV_HOME_DIR,
				StreamsMesosConstants.HOME_DIR_ARG, argsMap);
		if (propValue == null || propValue.equals("")) {
			throw new ResourceManagerException("HOME_DIR for Mesos Tasks not set.  Usually set to ${MESOS_SANDBOX}");
		}
		
		// Streams Domain Id
		propValue = setConfigPropertyWithEnv(StreamsMesosConstants.PROPS_STREAMS_DOMAIN_ID, props,
				StreamsMesosConstants.ENV_STREAMS_DOMAIN_ID,
				StreamsMesosConstants.DOMAIN_ID_ARG, argsMap);	
		
		// Framework Name in Mesos
		setConfigProperty(StreamsMesosConstants.PROPS_FRAMEWORK_NAME, props,
				String.valueOf(StreamsMesosConstants.FRAMEWORK_NAME_DEFAULT));
		
		// Mesos user to run task
		setConfigProperty(StreamsMesosConstants.PROPS_MESOS_USER, props,
				String.valueOf(StreamsMesosConstants.MESOS_USER_DEFAULT));
		
		// Cores to use for Domain Controller task
		setConfigProperty(StreamsMesosConstants.PROPS_DC_CORES, props,
				String.valueOf(StreamsMesosConstants.DC_CORES_DEFAULT));		
		
		// Memory to use for Domain Controller task
		setConfigProperty(StreamsMesosConstants.PROPS_DC_MEMORY, props,
				String.valueOf(StreamsMesosConstants.DC_MEMORY_DEFAULT));		
	
		// Wait time for synchronous resource requests (e.g. domain startup)
		setConfigProperty(StreamsMesosConstants.PROPS_WAIT_SYNC, props,
				String.valueOf(StreamsMesosConstants.WAIT_SYNC_DEFAULT));
		
		// Wait time for asynchronous resource requests
		setConfigProperty(StreamsMesosConstants.PROPS_WAIT_ASYNC, props,
				String.valueOf(StreamsMesosConstants.WAIT_ASYNC_DEFAULT));	
		
		// Wait time for flexible resource requests
		setConfigProperty(StreamsMesosConstants.PROPS_WAIT_FLEXIBLE, props,
				String.valueOf(StreamsMesosConstants.WAIT_FLEXIBLE_DEFAULT));	
		
		// WAIT_ALLOCATED_SECS
		setConfigProperty(StreamsMesosConstants.PROPS_WAIT_ALLOCATED, props,
				String.valueOf(StreamsMesosConstants.WAIT_ALLOCATED_SECS_DEFAULT));
		
		// Provisioning work directory: Location where we have Streams build resource package for deployments
		setConfigProperty(StreamsMesosConstants.PROPS_PROVISIONING_WORKDIR_PREFIX, props,
				String.valueOf(StreamsMesosConstants.PROVISIONING_WORKDIR_PREFIX_DEFAULT));
		
		// Fetch Parent URI: Location where Mesos fetcher will look for URI's we make available
		setConfigProperty(StreamsMesosConstants.PROPS_MESOS_FETCH_PARENT_URI, props,
				String.valueOf(StreamsMesosConstants.MESOS_FETCH_PARENT_URI_DEFAULT));

	
	}
	
	private String setConfigPropertyWithEnv(String propName, Properties props, String envVar, String argName, Map<String, String> argsMap) {
		String envValue = System.getenv(envVar);
		setConfigPropertyWithArg(propName, props, envValue, argName, argsMap);
		return _config.getProperty(propName);
	}
	
	private String setConfigPropertyWithArg(String propName, Properties props, String defaultValue, String argName, Map<String,String> argsMap) {
		setConfigProperty(propName, props, defaultValue);
		// Override with Argument if it was passed
		if (argName != null) {
			if (argsMap.containsKey(argName)) {
				_config.setProperty(propName, argsMap.get(argName));
			}
		}
		return _config.getProperty(propName);
	}
	
	private String setConfigProperty(String propName, Properties props, String defaultValue) {
		_config.setProperty(propName, props.getProperty(propName,defaultValue));
		return _config.getProperty(propName);
	}
	
	
	


	//////////////////////////////////////////////////
	// Streams ResourceManager Implementation
	//////////////////////////////////////////////////

	/*
	 * (non-Javadoc) Initialize Resource Manager when resource server starts
	 *
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#initialize()
	 */
	@Override
	public void initialize() throws ResourceManagerException {
		LOG.info("Streams Mesos Resource Manager Initializing...");
		
		_state = new StreamsMesosState(this);

		try {
			// Provision Streams if necessary
			// Caution, this can take some time and cause timeouts on slow machines
			// or workstations that are overloaded
			// In testing, saw issues where Streams Resource Manager Server would
			// disconnect client.
			//if (_argsMap.containsKey(StreamsMesosConstants.DEPLOY_ARG)) {
			if (_deployStreams) {
				LOG.debug("Deploy flag set.  Calling provisionStreams...");
				provisionStreams(_uriList, Utils.getProperty(_config, StreamsMesosConstants.PROPS_MESOS_FETCH_PARENT_URI));
			}
	
			// Get the streams master 
			// Priority: Argument, Property, Default
			_master = Utils.getProperty(_config, StreamsMesosConstants.PROPS_MESOS_MASTER);
			LOG.debug("Mesos master set to: " + _master);
	
			// Setup and register the Mesos Scheduler
			LOG.trace("About to call runMesosScheduler...");
	
			runMesosScheduler(_master, _state);
	
			LOG.debug("StreamsMesosResourceFramework.initialize() complete");
		} catch (StreamsMesosException e) {
			LOG.error("Error caught initializing StreamsResourceManager: " + e.toString());
			throw new ResourceManagerException(e.toString(), e);
		}
		LOG.info("Streams Mesos Resource Manager ready to receive requests from IBM Streams");
	}

	
	
	/* (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#isFeatureSupported(com.ibm.streams.resourcemgr.ResourceManager.Feature)
	 */
	@Override
	public boolean isFeatureSupported(Feature feature) {
		LOG.trace("isFeatureSupported called with feature: " + feature.toString());
		// handle any features that we do not know about
		boolean supported = super.isFeatureSupported(feature);
		
		// handle the features we do know about
		switch (feature) {
		case HIGH_AVAILABILITY:
			// Set to false for version 0.5
			// It is not complete (need state saved in zookeeper)
			supported = false;
		}
		LOG.trace("isFeatureSupported returning: " + supported);
		return supported;
	}

	
	
	
	
	/* (non-Javadoc) Determine if we want to allow Resource Server to shutdown.
	 * Raise 
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#validateStop()
	 */
	@Override
	public void validateStop() throws ResourceManagerException {
		// TODO Auto-generated method stub
		LOG.info("VALIDATE STOP Called by ResourceManagerServer");
		super.validateStop();
	}

	/*
	 * (non-Javadoc) Close/Shutdown/Cleanup Resource Manager when resource
	 * server stops
	 * 
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#close()
	 */
	@Override
	public void close() {
		LOG.info("Streams Mesos Resource Manager shutting down at the request of the Streams Resource Server");
		if (_driver != null) {
			LOG.debug("stopping the mesos driver...");
			Protos.Status driverStatus = _driver.stop();
			LOG.debug("...driver stopped, status: " + driverStatus.toString());
		}
		LOG.info("Streams Mesos Resource Manager shutdown complete");
	}
	
	
    /* (non-Javadoc)
     * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#clientConnected(com.ibm.streams.resourcemgr.ClientInfo)
     */
	public void clientConnected(ClientInfo clientInfo) {
		LOG.debug("Client connected: " + clientInfo);
		if (clientInfo.getClientName().equals("controller")) {
			ClientInfo saved = _state.getClientInfo(clientInfo.getClientId());
			if (saved != null) {
				String version = clientInfo.getInstallVersion();
				String savedVersion = saved.getInstallVersion();

				// install version has changed
				if (version != null && savedVersion != null && !version.equals(savedVersion)) {
					LOG.debug("##### client=" + saved.getClientId() + " changed install version from=" + savedVersion
							+ " to=" + version);
				}
			}
			// update client in state
			_state.setClientInfo(clientInfo);

		}
	}
	
	
	// Implement custom command handler to at least get internal state displayed
	// Future should implement an HTTP interface and REST endpoint
    @Override
	public String customCommand(ClientInfo client, String commandData, Locale locale) throws ResourceManagerException {
    	LOG.info("Custom command received");
    	LOG.debug("  command: " + commandData);
    	ObjectMapper mapper = new ObjectMapper();
 
    	
    	ObjectNode request;
    	try {
    		request = (ObjectNode) mapper.readTree(commandData);
    	} catch (Exception e) {
    		LOG.error("Error parsing Custom Command from client " + client.getClientId());
    		throw new ResourceManagerException("Error parsing Custom Command from client " + client.getClientId(),e);
    	}
    	
    	ObjectNode response = mapper.createObjectNode();
    	response.put(StreamsMesosConstants.CUSTOM_COMMAND_RETURN_CODE, new Integer(-1));
    	String requestId = request.get(StreamsMesosConstants.CUSTOM_COMMAND).asText();
    	if (requestId == null) {
    		throw new ResourceManagerException("Custom Command not specified");
    	}
    	
    	switch (requestId) {
    	case StreamsMesosConstants.CUSTOM_COMMAND_GET_RESOURCE_STATE:
    		getResourceState(request, response, client);
    		break;
    	default:
    		throw new ResourceManagerException("Unknown command: " + requestId);
    	}
    	return response.toString();
	}
    

	@Override
    public void validateTags(ClientInfo client, ResourceTags tags, Locale locale) throws ResourceTagException,
            ResourceManagerException {
		LOG.trace("StreamsResourceServer called validateTags() with: " + tags.toString());
        for (String tag : tags.getNames()) {
            TagDefinitionType definitionType = tags.getTagDefinitionType(tag);
            switch (definitionType) {
                case NONE:
                    //no definition means use defaults
                    break;
                case PROPERTIES:
                    Properties propsDef = tags.getDefinitionAsProperties(tag);
                    for (Object key : propsDef.keySet()) {
                        validateTagAttribute(tag, (String)key, propsDef.get(key));
                    }
                    break;
                default:
                    throw new ResourceTagException("Unexpected properties in definition for tag: " + tag);
            }
        }
    }



	/*
	 * Create master resource. This resource is the first resource requested by
	 * Streams and is requested when the streams domain starts
	 * 
	 * @see
	 * com.ibm.streams.resourcemgr.ResourceManagerAdapter#allocateMasterResource
	 * (com.ibm.streams.resourcemgr.ClientInfo,
	 * com.ibm.streams.resourcemgr.AllocateMasterInfo)
	 */
	@Override
	public ResourceDescriptor allocateMasterResource(ClientInfo clientInfo, AllocateMasterInfo request)
			throws ResourceTagException, ResourceManagerException {
		LOG.info("Received request to allocate master resource");
		LOG.debug("#################### allocate master start ####################");
		LOG.debug("Request: " + request);
		LOG.debug("ClientInfo: " + clientInfo);
		
		ResourceDescriptor descriptor = null;
		
		List<ResourceDescriptorState> lst = allocateResources(clientInfo, true, 1, request.getTags(),
				AllocateType.SYNCHRONOUS);
		if (lst.size() == 0)
			throw new ResourceManagerException("Streams Mesos Resource Manager could not allocate master resource");
		
		descriptor =  lst.get(0).getDescriptor();

		LOG.debug("#################### allocate master end ####################");

		return descriptor;
	}

	/*
	 * Create non-master resources. These resources are used for Streams
	 * instances
	 * 
	 * @see
	 * com.ibm.streams.resourcemgr.ResourceManagerAdapter#allocateResources(com.
	 * ibm.streams.resourcemgr.ClientInfo,
	 * com.ibm.streams.resourcemgr.AllocateInfo)
	 */
	@Override
	public Collection<ResourceDescriptorState> allocateResources(ClientInfo clientInfo, AllocateInfo request)
			throws ResourceTagException, ResourceManagerException {
		LOG.info("Received request to allocate " + request.getCount() + " resources for instance " + request.getInstanceId() );
		LOG.debug("################### allocate start ###################");
		LOG.debug("Request: " + request);
		LOG.debug("ClientInfo: " + clientInfo);
		
		Collection<ResourceDescriptorState> states = new ArrayList<ResourceDescriptorState>();
		
		states = allocateResources(clientInfo, false, request.getCount(), request.getTags(), request.getType());

		
		LOG.debug("################### allocate end ###################");

		return states;

	}
	
	/**
	 * Releases specified resources that are allocated 
	 *
	 * (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#releaseResources(com.ibm.streams.resourcemgr.ClientInfo, java.util.Collection, java.util.Locale)
	 */
	@Override
	public void releaseResources(ClientInfo client, Collection<ResourceDescriptor> descriptors, Locale locale)
			throws ResourceManagerException {
		LOG.debug("################ release specified resources start ################");

		List<String> resourceList = new ArrayList<String>();
		for (ResourceDescriptor rd : descriptors) {
			resourceList.add(rd.getDisplayName());
		}
		String resourceListOutput = StringUtils.join(resourceList, ",");
		LOG.info("Recieved request to release resources: " + resourceListOutput);
		
		LOG.debug("client: " + client);;
		LOG.debug("descriptors: " + descriptors);
		LOG.debug("locale: " + locale);
		for (ResourceDescriptor rd : descriptors) {
			_state.releaseResource(rd);
		}
		
		LOG.debug("################ release specified resources end ################");

	}

	/**
	 * Releases all resources for the given client
	 * NOTE: At this time has not been tested or coded for multiple clients
	 * 
	 * (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#releaseResources(com.ibm.streams.resourcemgr.ClientInfo, java.util.Locale)
	 */
	@Override
	public void releaseResources(ClientInfo client, Locale locale) throws ResourceManagerException {
		LOG.info("Received request to release all resources for client: " + client.getClientName());
		LOG.debug("################ release all client resources start ################");
		LOG.debug("client: " + client);;
		LOG.debug("locale: " + locale);
		_state.releaseAllResources(client);
		LOG.debug("################ release all client resources end ################");
	}

	
	/**
	 * Tells us Streams no longer needs the resource we reported as pending
	 * 
	 * (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#cancelPendingResources(com.ibm.streams.resourcemgr.ClientInfo, java.util.Collection, java.util.Locale)
	 */
	@Override
	public void cancelPendingResources(ClientInfo client, Collection<ResourceDescriptor> descriptors, Locale locale)
			throws ResourceManagerException {
		LOG.debug("################ cancel Pending Resources start ################");
		
		List<String> resourceList = new ArrayList<String>();
		for (ResourceDescriptor rd : descriptors) {
			resourceList.add(rd.getDisplayName());
		}
		String resourceListOutput = StringUtils.join(resourceList, ",");
		LOG.info("Recieved request to cancel pending resources: " + resourceListOutput);
		
		LOG.debug("client: " + client);
		LOG.debug("descriptors: " + descriptors);
		LOG.debug("locale: " + locale);
		for (ResourceDescriptor rd : descriptors) {
			_state.cancelPendingResource(rd);
		}
		
		LOG.debug("################ cancel Pending Resources end ################");
	}
	
	
	
	

	/* (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#error(java.lang.String)
	 */
	@Override
	public void error(String message) {
		//super.error(message);
		LOG.error("Error received from StreamsResourceServer: " + message);
	}

	/* (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#error(java.lang.Throwable)
	 */
	@Override
	public void error(Throwable throwable) {
		//super.error(throwable);
		if (throwable != null) {
			LOG.error("Error received from StreamsResourceServer: " + throwable.getMessage());
			throwable.printStackTrace();
		}
	}

	/* (non-Javadoc)
	 * @see com.ibm.streams.resourcemgr.ResourceManagerAdapter#trace(java.lang.String)
	 */
	@Override
	public void trace(String message) {
		//super.trace(message);
		LOG.debug("Streams: " + message);

	}

	
/////////////////////////////////////////////
/// MESOS FRAMEWORK INTEGRATION
/////////////////////////////////////////////


	
	
	
	private FrameworkInfo getFrameworkInfo() {
		LOG.trace("Creating FrameworkInfo");
		FrameworkInfo.Builder builder = FrameworkInfo.newBuilder();
		builder.setFailoverTimeout(60*60*24*7);  // 1 week
		builder.setUser(Utils.getProperty(_config, StreamsMesosConstants.PROPS_MESOS_USER));
		builder.setName(Utils.getProperty(_config, StreamsMesosConstants.PROPS_FRAMEWORK_NAME));
		
		// Test setting role
		//builder.setRole("streams");
		
		// Get framework ID from state in case this is a failover.
		String savedFrameworkId = _state.getMesosFrameworkId();
		if (savedFrameworkId != null) {
			LOG.trace("framworkId found in state.  Must be failover.  ID: " + savedFrameworkId);
			builder.setId(Protos.FrameworkID.newBuilder().setValue(_state.getMesosFrameworkId()).build());
		} else {
			LOG.trace("frameworkId not found in state, must be initial run of leader");
		}
		return builder.build();
	}

	private void runMesosScheduler(String mesosMaster, StreamsMesosState state) throws StreamsMesosException {
		// private void runMesosScheduler(List<CommandInfo.URI>uriList, String
		// mesosMaster) {
		LOG.debug("Creating new Mesos Scheduler...");
		// LOG.info("URI List: " + uriList.toString());
		// LOG.info("commandInfo: " + getCommandInfo(uriList));;

		_scheduler = new StreamsMesosScheduler(this, state);

		LOG.debug("Creating new MesosSchedulerDriver...");
		FrameworkInfo fwInfo = getFrameworkInfo();
		_driver = new MesosSchedulerDriver(_scheduler, fwInfo, mesosMaster);

		LOG.debug("About to start the mesos scheduler driver...");
		Protos.Status driverStatus = _driver.start();
		LOG.debug("...start returned status: " + driverStatus.toString());
		if (driverStatus != Protos.Status.DRIVER_RUNNING) {
			throw new StreamsMesosException(_scheduler.getLastErrorMessage());
		}
		LOG.info("Mesos Framework scheduler started: " + fwInfo.getName());
		_state.setScheduler(_scheduler);
	}


	
	///////////////////////////////////////////////
	/// Resource and Tag METHODS
	///////////////////////////////////////////////
	

	
    private void validateTagAttribute(String tag, String key, Object valueObj) throws ResourceTagException {
        //memory, cores
    	// Value of 0 means use all resources
    	double value;
    	
        if (key.equals(StreamsMesosConstants.MEMORY_TAG) || key.equals(StreamsMesosConstants.CORES_TAG)) {
            if (valueObj == null) {
                 throw new ResourceTagException("Tag: " + tag + " property: " + key + " must not be empty if it is present.");
            } else if (valueObj instanceof String) {
                try {
                    value = Double.parseDouble(valueObj.toString().trim());
                } catch (NumberFormatException nfe) {
                    throw new ResourceTagException("Tag: " + tag + " property: " + key + " must contain a numeric value.");
                }
            } else if (!(valueObj instanceof Double) && !(valueObj instanceof Integer)) {
                throw new ResourceTagException("Tag: " + tag + " property: " + key + " must contain a numeric value.");
            } else {
            	value = (double)valueObj;
            }
        } else {
            throw new ResourceTagException("Tag: " + tag + " contains an unsupported attribute: " + key);
        }
        
        if (value < 0.0) {
        	throw new ResourceTagException("Tag: " + tag + " property: " + key + " must be have a value >= 0");
        }
        
    }
    
	/*
	 * Create resources helper for both master and regular resources
	 *
	 */
	private List<ResourceDescriptorState> allocateResources(ClientInfo clientInfo, boolean isMaster, int count,
			ResourceTags tags, AllocateType rType) throws ResourceManagerException, ResourceTagException {

		List<StreamsMesosResource> newRequestsFromStreams = new ArrayList<StreamsMesosResource>();
		
		String domainId = clientInfo.getDomainId();
		String zkConnect = clientInfo.getZkConnect();
		
		// Transition to where we support multiple domains but for now we only support one
		// Ensure that the domain for the client is what the argument specified
		// Should never have a mismatch because ResourceServer would no allow domain to start
		// if Resource manager was not registered to handle multiple domains
		String configuredDomainId = Utils.getProperty(_config,StreamsMesosConstants.PROPS_STREAMS_DOMAIN_ID);
		if (!domainId.equals(configuredDomainId)) {
			LOG.error("Domain ID of resource request (" + domainId + 
					") does not match domainID registered for this resource manager (" + configuredDomainId	 + ")");
			throw new ResourceManagerException("Domain ID of resource request (" + domainId + 
					") does not match domainID registered for this resource manager (" + configuredDomainId + ")");
		}
		
		// Same temporary check for zookeeper, again, should never happen
		String configuredZk = Utils.getProperty(_config, StreamsMesosConstants.PROPS_ZK_CONNECT);
		if (!zkConnect.equals(configuredZk)) {
			LOG.error("ZK_CONNECT of resource request (" + zkConnect + 
					") does not match ZK_CONNECT registered for this resource manager (" + configuredZk + ")");
			throw new ResourceManagerException("ZK_CONNECT of resource request (" + zkConnect + 
					") does not match ZK_CONNECT registered for this resource manager (" + configuredZk + ")");
		}
	
		
		for (int i = 0; i < count; i++) {
			// Creates new Resource, queues, and adds to map of all resources
			StreamsMesosResource smr = _state.createNewResource(clientInfo, tags, isMaster);
			// Put it in our local list to wait a little bit of time to see if it gets started
			newRequestsFromStreams.add(smr);
		}

		return waitForAllocation(newRequestsFromStreams, rType);

	}

	/**
	 * Attempt to wait a little bit to see if we can get resources allocated
	 * Note: If streams is being deployed to the containers, that can take a
	 * while.
	 * Notify pending if we wait and they are not allocated
	 * 
	 * @param newAllocationRequests
	 * @param rType
	 * @return
	 */
	private List<ResourceDescriptorState> waitForAllocation(List<StreamsMesosResource> newAllocationRequests,
			AllocateType rType) {
		// Depending on the type (Synchronous, Asynchronous, Flexible, etc...)
		// figure out how long to wait
		int waitTimeSecs = getWaitSecs(rType);
		LOG.debug("Waiting for the Streams Mesos Scheduler to allocate and run " + newAllocationRequests.size() + " resources, maxTime: "
				+ (waitTimeSecs < 0 ? "unbounded" : waitTimeSecs));
		long endTime = System.currentTimeMillis() + (waitTimeSecs * 1000);

		// Wait and poll to see if any are allocated in the given time
		int runningCount = 0;
		while (waitTimeSecs < 0 || System.currentTimeMillis() < endTime) {
			synchronized (this) {
				LOG.trace("Polling the new requests...");
				for (StreamsMesosResource smr : newAllocationRequests) {
					LOG.trace("smr {id: " + smr.getId() + ", state: " + smr.getResourceState().toString() + "}");
					// Ensure it is running and has been for our minimum duration
					// We use a minimum duration to prevent reporting quick failures as allocated, only to have to turn around
					// and revoke them.  This is usually only in the case of a slave not configured properly (e.g. Streams not found)
					if (smr.isRunning() && (smr.getResourceStateDurationSeconds() > _waitAllocatedSecs)) {
						LOG.debug("Resource " + smr.getId() + " Mesos task has been running longer than " + _waitAllocatedSecs + ".  Will notify after waiting for all requests.");
						runningCount++;
					}
				}
				LOG.trace("Allocated Count: " + runningCount);
				if (runningCount == newAllocationRequests.size()) {		
					// We have them all, no need to continue to wait
					LOG.debug("Allocated Count (" + runningCount + ") equals new allocation requests (" + newAllocationRequests.size() + "), stop waiting and polling");
					break;
				}
			}
			LOG.trace("...waiting");
			Utils.sleepABit(StreamsMesosConstants.SLEEP_UNIT_MILLIS);
		}
		LOG.debug("Finished waiting for new requests: running " + runningCount + " of " + newAllocationRequests.size() + " resources");

		// We have waited long enough
		// Now we need to notify Streams and set the requestState.  
		// Synchronize on the whole _state because we need to really snapshot what we have told Streams
		// so we know how to react to future task status changes
		synchronized (_state) {
			List<ResourceDescriptorState> descriptorStates = new ArrayList<ResourceDescriptorState>();
			// Loop through them and set PENDING state for those not allocated so we know to notify
			// when they are allocated
			for (StreamsMesosResource smr : newAllocationRequests) {
				descriptorStates.add(smr.getDescriptorState());
				// Now notify streams of the allocation or pending status of each resource
				if (smr.isRunning()) {
					LOG.info("Notifying Streams that the resource " + smr.getId() + " is allocated");
					_state.setAllocated(smr.getId());
				}
				if (!smr.isAllocated()) {
					LOG.info("Notifying Streams that resource " + smr.getId() + " is pending");
					_state.setPending(smr.getId());
				}
			}
			LOG.debug("Returning descriptorStates: " + descriptorStates);
			return descriptorStates;
		}
	}

	/**
	 * Lookup for how long to wait before reporting back to Streams the
	 * allocation request is complete or pending
	 * 
	 * @param rType
	 * @param props
	 * @return
	 */
	private int getWaitSecs(AllocateType rType) {
		switch (rType) {
		case SYNCHRONOUS:
			return Utils.getIntProperty(_config, StreamsMesosConstants.PROPS_WAIT_SYNC);
		case ASYNCHRONOUS:
			return Utils.getIntProperty(_config, StreamsMesosConstants.PROPS_WAIT_ASYNC);
		case FLEXIBLE:
			return Utils.getIntProperty(_config, StreamsMesosConstants.PROPS_WAIT_FLEXIBLE);
		default:
			throw new RuntimeException("Unhandled Streams AllocateType: " + rType);
		}
	}
	
	///////////////////////////////////////////////
	// STREAMS PROVISIONING METHODS
	///////////////////////////////////////////////
	
	/*
	 * If we are not going to pre-install Streams, then we need to ensure it is
	 * fetched by the executor
	 */
	private void provisionStreams(List<Protos.CommandInfo.URI> uriList,
			String destinationRoot) throws StreamsMesosException, ResourceManagerException {
		String streamsInstallable = null;
		String workingDirFile = null;
		LOG.info("Preparing Streams deployable resource package");
		LOG.debug("Creating Streams Installable in work location.");
		workingDirFile = Utils.getProperty(_config, StreamsMesosConstants.PROPS_PROVISIONING_WORKDIR_PREFIX) + "." + (System.currentTimeMillis() / 1000);

		try {
			if (Utils.createDirectory(workingDirFile) == false) {
				LOG.error("Failed to create working directory for (" + workingDirFile + ")");
				throw new StreamsMesosException("Failed to create working directory");
			}

			streamsInstallable = ResourceManagerUtilities.getResourceManagerPackage(workingDirFile,
					ResourceManagerPackageType.BASE_PLUS_SWS_SERVICES);
		} catch (Exception e) {
			LOG.error("Failed to create Streams Resource Manager Package: " + e.toString());
			throw new StreamsMesosException("Failed to create Streams Resource Manager Package", e);
		}

		LOG.debug("Created Streams Installable: " + streamsInstallable);

		// Get it to where we need it
		LOG.debug("Looking at destinationRoot(" + destinationRoot + ") to determine filesystem");
		String destinationURI;
		String destinationPath; // without prefix
		if (destinationRoot.startsWith("file://")) {
			destinationPath = destinationRoot.replaceFirst("file:/", "");
			/*** Local File System Version ***/
			LOG.info("Copying Streams Installable to shared location (" + destinationPath + ")...");
			String destPathString;
			try {
				destPathString = LocalFSUtils.copyToLocal(streamsInstallable, destinationPath);
			} catch (IOException e) {
				LOG.error("Failed to copy streamsInstallable(" + streamsInstallable
						+ ") to provisioining shared location (" + destinationPath + ")");
				LOG.error("Exception: " + e.toString());
				throw new StreamsMesosException("Failed to provision Streams executable tar to local FS: ", e);
			}
			// Needs to be an absolute path for Mesos
			destinationURI = "file://" + destPathString;
		} else if (destinationRoot.startsWith("hdfs://")) {
			destinationPath = destinationRoot.replaceFirst("hdfs:/", "");
			/*** Hadoop File System Version ***/
			LOG.info("Copying Stream Installable to HDFS location (" + destinationPath + ")");
			Path hdfsStreamsPath;
			try {
				FileSystem hdfs = HdfsFSUtils.getHDFSFileSystem();
				hdfsStreamsPath = HdfsFSUtils.copyToHDFS(hdfs, destinationPath, streamsInstallable,
						new Path(streamsInstallable).getName());
			} catch (IOException e) {
				LOG.error("Failed to copy streamsInstallable(" + streamsInstallable
						+ ") to provisioining HDFS location (" + destinationPath + ")");
				LOG.error("Exception: " + e.toString());
				throw new StreamsMesosException("Failed to provision Streams executable tar to HDFS: ", e);
			}
			destinationURI = hdfsStreamsPath.toString();
		} else {
			// Should handle http:// in the future
			LOG.error("Unexpected/Unhandled Provsioning Directory URI prefix: " + destinationRoot);
			throw new StreamsMesosException(
					"Unexpected/Unhandled Provsioning Directory URI prefix: " + destinationRoot);
		}

		// Remove working directory
		LOG.debug("Deleting Streams Installable from work location...");
		Utils.deleteDirectory(workingDirFile);
		LOG.debug("Deleted: " + streamsInstallable);

		// Create Mesos URI for provisioned location
		LOG.debug("Creating URI for: " + destinationURI);
		CommandInfo.URI.Builder uriBuilder;
		uriBuilder = CommandInfo.URI.newBuilder();
		// uriBuilder.setCache(true);
		uriBuilder.setCache(false);
		uriBuilder.setExecutable(false);
		uriBuilder.setExtract(true);
		uriBuilder.setValue(destinationURI);

		uriList.add(uriBuilder.build());
		LOG.debug("Created URI");
	}
	
	//////////////////////////////////////
	/// CUSTOM COMMANDS IMPLEMENTATION ///
	//////////////////////////////////////
	
	// Future put it its own class
	public void getResourceState(ObjectNode request, ObjectNode response, ClientInfo client) {
		ArrayNode resources = response.putArray(StreamsMesosConstants.CUSTOM_RESULT_RESOURCES);
		
		boolean longVersion = request.get(StreamsMesosConstants.CUSTOM_PARM_LONG).asBoolean(false);
		
		Map<String,StreamsMesosResource> allResources = _state.getAllResources();
		for (StreamsMesosResource smr : allResources.values()) {
			resources.add(smr.resourceStateAsJsonObjectNode(longVersion));
		}
		
		response.put(StreamsMesosConstants.CUSTOM_COMMAND_RETURN_CODE, 0);
	}
}
