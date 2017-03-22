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
 * Constants for use by the Streams Mesos Resource Manager
 *
 * Many of these will migrate from constants to configuration parameters,
 * in which case, these will be default values.
 *
 * @author Brian M Williams
 *
 */
public class StreamsMesosConstants {

	/* Turn these into arguments */
	public static final String
		MESOS_MASTER_DEFAULT = "zk://localhost:2181/mesos"
	;

	/* Identification Constants */
	public static final String
		RESOURCE_TYPE_DEFAULT = "mesos",
		MESOS_TASK_ID_PREFIX = "streams_",
		FRAMEWORK_NAME_DEFAULT = "IBMStreamsRM",
		MESOS_USER_DEFAULT = ""
	;
	
	/* Environment Variables */
	public static final String
		ENV_STREAMS_ZKCONNECT = "STREAMS_ZKCONNECT",
		ENV_STREAMS_INSTALL = "STREAMS_INSTALL",
		ENV_HOME_DIR = "HOME",
		ENV_STREAMS_DOMAIN_ID = "STREAMS_DOMAIN_ID"
	;

	/* Names of command line arguments */
	public static final String
		// url of mesos master e.g. (zk://host1:port1,host2:port2,.../mesos | localhost:5050)
		MESOS_MASTER_ARG = "--master",
		// streams zookeeer connect string (eg. "hosta:port1,hostb:port2,...")
		// may or may not be same zookeeper as mesos is using
		ZK_ARG = "--zkconnect",
		// Streams resource manager type (defaulting to RESOURCE_TYPE above)
		TYPE_ARG = "--type",
		// Java class name of manager
		MANAGER_ARG = "--manager",
		// location of streams installation if not deployed
		STREAMS_INSTALL_PATH_ARG = "--install-path",
		HOME_DIR_ARG = "--home-dir",
		// Flag to initiate deploying the streams resource package for tasks
		DEPLOY_ARG = "--deploy",
		PROP_FILE_ARG = "--properties",
		DOMAIN_ID_ARG = "--domain-id"
	;

	/* Property file and properties */
	public static final String
		RM_PROPERTIES_FILE = "streams-mesos.properties"
	;
	// Streams Mesos Property and Configuration Names
	public static final String
		PROPS_MESOS_MASTER="MESOS_MASTER",
		PROPS_ZK_CONNECT="STREAMS_ZKCONNECT",
		PROPS_DEPLOY="DEPLOY",
		PROPS_USER_HOME="HOME_DIR",
		PROPS_STREAMS_DOMAIN_ID="STREAMS_DOMAIN_ID",
		PROPS_RESOURCE_TYPE="RESOURCE_TYPE",
		PROPS_FRAMEWORK_NAME="MESOS_FRAMEWORK_NAME",
		PROPS_MESOS_USER="MESOS_USER",
		PROPS_DC_CORES="DC_CORES",
		PROPS_DC_MEMORY="DC_MEMORY",
		PROPS_WAIT_SYNC = "WAIT_SYNC_SECS",
		PROPS_WAIT_ASYNC = "WAIT_ASYNC_SECS",
		PROPS_WAIT_FLEXIBLE="WAIT_FLEXIBLE_SECS",
		PROPS_WAIT_ALLOCATED="WAIT_ALLOCATED_SECS",
		PROPS_MESOS_FETCH_PARENT_URI="MESOS_FETCH_PARENT_URI",
		PROPS_PROVISIONING_WORKDIR_PREFIX="PROVISIONING_WORKDIR_PREFIX",
		PROPS_STREAMS_INSTALL_PATH="STREAMS_INSTALL_PATH"
	;
	
	public static final double
		WAIT_SYNC_DEFAULT=30,
		WAIT_ASYNC_DEFAULT=5,
		WAIT_FLEXIBLE_DEFAULT=5
	;
	
	public static final long
		WAIT_ALLOCATED_SECS_DEFAULT=2
	;
	
	/* Mesos resource allocation defaults */
	public static final double
		DC_MEMORY_DEFAULT = 2048,
		DC_CORES_DEFAULT = 1
	;

	/* Constants for specifying to use all resources in an offer */
	public static final double
		USE_ALL_CORES = 0,
		USE_ALL_MEMORY = 0
	;

	/* Streams tag names */
	public static final String
		MEMORY_TAG = "memory",
		CORES_TAG = "cores"
	;
	
	/* Polling interval, how long to sleep between polling checks */
	public static final long SLEEP_UNIT_MILLIS = 500;

	
	/* Streams provisioning constants */
	public static final String
		// Location to have Streams build resource package
		PROVISIONING_WORKDIR_PREFIX_DEFAULT = "/tmp/streams.mesos",
		// Default location of location to stage resources for mesos to fetch
		// Needs to be accessible by all nodes (file://, hdfs://, http://)
		//MESOS_FETCH_PARENT_URI_DEFAULT = "hdfs://streams_mesos_provision",
		MESOS_FETCH_PARENT_URI_DEFAULT = "file://tmp",
		RES_STREAMS_BIN = "StreamsResourceInstall.tar",
		RES_STREAMS_BIN_NAME = "STREAMS_BIN"
	;
	
	/* Custom Command Constants */
	public static final String
		CUSTOM_COMMAND="command",
		CUSTOM_COMMAND_GET_RESOURCE_STATE="getresourcestate",
		CUSTOM_ARG_LONG="-l",
		CUSTOM_PARM_LONG="long",
		CUSTOM_RESULT_RESOURCES="resources",
		CUSTOM_RESULT_RESOURCE_ID="nativeId",
		CUSTOM_RESULT_RESOURCE_STREAMS_ID="id",
		CUSTOM_RESULT_RESOURCE_TASK_ID="mesosTaskId",
		CUSTOM_RESULT_RESOURCE_RESOURCE_STATE="resourceState",
		CUSTOM_RESULT_RESOURCE_REQUEST_STATE="requestState",
		CUSTOM_RESULT_RESOURCE_COMPLETION_STATUS="taskCompletionStatus",
		CUSTOM_RESULT_RESOURCE_HOST_NAME="hostname",
		CUSTOM_RESULT_RESOURCE_IS_MASTER="master",
		CUSTOM_RESULT_RESOURCE_TAGS="tags",
		CUSTOM_RESULT_RESOURCE_CORES="cores",
		CUSTOM_RESULT_RESOURCE_MEMORY="memory",

		CUSTOM_COMMAND_RETURN_CODE="rc"
	;

}
