# Readme file for setting up Apache Mesos support for IBM® InfoSphere Streams
This Readme file describes how to install and configure Apache Mesos for InfoSphere® Streams.

## Questions
For questions and issues, please contact:

Brian M Williams, IBM<br>
bmwilli@us.ibm.com

## UNDER CONSTRUCTION
Please understand that code and this README are at a point in time of ongoing initial development twoards two initial releases:
* Release 0.5 - Functional release without High Availability and Failover
* Release 1.0 - Functional release with High Availability and Failover

See DEVELOP.txt file for notes on what needs to be done

# Contents
1. [Building the Streams Mesos Resource Manager Framework](#building-the-resource-manager)
2. [Architecture and Design](#architecture-and-design)
3. [Running the Resource Manager](#running-the-resource-manager)
4. [Commands](#commands)
5. [Resource Tags](#resource-tags)
6. [Deploy via Marathon](#deploy-via-marathon)
7. [Logging](#logging)

# Building the Resource Manager

## Compiling the application
`mvn compile`

## Create deployable Package
`mvn package`

Package includes:
* streams-on-mesos: python script to start/stop/status resource manager
* streams-mesos.properties: default properties file
* streams-on-mesos jar with dependencies: resource manager package of java class files
	
## Install locally for testing
`mvn install`

This installs the streams-on-mesos jar into a lib directory at the same level as the pom.xml file.  The streams-on-mesos script in the scripts directory will check in this relative location when it is executed.  This allows for quickly testing the script and properties file from the same location as they are developed.

## Install in another location
`tar -xvf target/streams-on-mesos-0.0.1-SNAPSHOT.tar.gz -C <install location>`

Extract the contents of the tar.gz file into a directory of your choice, change to that directory, and run the application from the scripts directory.


# Architecture and Design
The current implementation was adapted from other IBM Streams resource managers including the YARN and Symphony managers.  

## Mesos Approach to Resource Management
There is often questions and confusion with reagrds to Mesos being a resource manager that makes offers, rather than accepting requests (e.g. YARN).  In most cases (unless there are no resources left available) Mesos makes offers every second.  This is fast enough for the Streams Mesos Resource Manager to handle synchronous resource requests from Streams.

## A Tale of Two Threads - ResourceManagerAdapter and Mesos Scheduler
There are two primary threads running in this Resource Manager.  The main class, StreamsMesosResourceManager, extends the Streams ResourceManagerAdapter class and listens for allocation and release requests from the StreamsResourceServer (launched by the streams-on-mesos script).
Simultaneously, the StreamsMesosScheduler class is running in a thread and listening for resource offers and other messages from Mesos.

Between these to classes sits the shared state of the StreamsMesosState and the collection of StreamsMesosResource objects.

The StreamsMesosResourceManager creates new StreamsMesosResource objects in the state and then waits for a period of time checking to see if the requests are RUNNNING before reporting back to Streams the request is ALLOCATED or PENDING.

The StreamsMesosScheduler receives offers and if there are outstanding requests available, it determines if one of the offers is large enough for the request, and if so, launches the task (starts the streams controller in a task) using the built-in Mesos CommandExecutor.

## Mesos Executor - CommandExecutor
The initial design and implementation uses the built-in CommandExecutor of Mesos for running the getStartControllerCommand(... fork=false...) returned command.  By setting fork=false Mesos keeps the parent process opened and shown as RUNNING in the Mesos web GUI.

When a resource is released, the ResourcemanagerUtilities.stopController() command is executed and the shutdown of the controller is completed and Mesos marks the task as FINISHED.



# Running the Resource Manager

## Deployment Decisions
The Streams Mesos Resource Manager supports two modes of streams runtime deployment: Pre-installed and Runtime-deployed
> NOTE: It is recommended to setup your Mesos cluster with Pre-installed Streams runtime.  This greatly reduces the startup time of Streams Domains and Instances.  

> NOTE: If you utilize the Runtime-deployed setup, it is suggested that you avoid making domain property changes when the streams-on-mesos Resource manager is running, the domain exists but is not running.  The reason is that Streams will attempt to start the Authentication and Authorization service, thus causing the a resource to be provisioned and released for the sole purpose of making a property change (which can take minutes to perform).  Instead, it is recommended that you shutdown the streams-on-mesos resource manager, remove and recreate the domain with the new properties.

### Runtime-deployed
The `--deploy` option enables you to take advantage of the InfoSphere Streams provisioning features. When you specify this option, Mesos copies and extracts the installer for each container. To avoid timeout issues for both the Mesos executor and Streams do the following:
#### Mesos
Increase the `executor_registration_timout` for all mesos slaves to at least 5mins before you use the Streams Resource Manager
>Example: `echo "5mins" > /etc/mesos-slave/executor_registration_timeout`

The reason for this change is that mesos only waits 1 minute by default for tasks to become active.  When runtime deployment is used, the Mesos fetcher must fetch and untar the Streams resource package.  Depending on the system resources, this can take more than 1 minute, causing the task to FAIL.
	
#### Streams
Set the domain property `controller.startTimeout` to at least 300 before you start the domain.
>Example: `streamtool mkdomain --property controller.startTimeout=300 --property domain.externalResourceManager=mesos`

The reason for this change is that mesos command executor must wait for the streams resource package to be installed before it can start the streams controller, however, it reports the task as RUNNING before this is complete.  Increasing this value allows Streams to wait patiently for this rather than failing the startdomain command.

### Pre-installed
If you choose not use the `--deploy` option, the Streams software should be installed on all of the mesos-slaves in a consistent location (e.g. /opt/ibm/InfoSphere_Streams)
Note: At this time, the location on the mesos slaves must be the same as on the node where `streams-on-mesos` command is run.
This will made into a property or argument in a future version.

## Run as user or run as root
The user that each Streams Controller runs as within the Mesos task is controlled by the following property file value:
>`MESOS_USER`

This value defaults to the user who executes the streams-on-mesos command (and thus the StreamsResourceServer).
## Run as root
If you are going to set the domain property `security.runAsRoot=true` then you need to set the MESOS_USER to root:
>`MESOS_USER=root`

## streams-on-mesos in Marathon
If you run streams-on-mesos via marathon, you control the user that runs it via the marathon json file used to submit it.
A null value defaults to the user that runs the Marathon framework (which is often root)
>`"user":null`

If this is the case, the default user for the streams-on-mesos controller tasks will be root

>Bottom line: control the users of the streams-on-mesos task and the subsequent controller tasks carefully


# Commands:

## Create Domain
`streamtool mkdomain --property domain.externalResourceManager=mesos`

## Start Mesos Resource Manager (pre-installed Streams)
`./streams-on-mesos start --master zk://172.31.29.41:2181/mesos`

## Status of Mesos Resource Manager Process
`./streams-on-mesos status -d StreamsDomain`
<pre>
Host                                         Port   PID    Status   Version
ip-172-31-29-41.ec2.internal (172.31.29.41)  33202  30715  RUNNING  4.2.0.0
</pre>

## Start the Domain
`streamtool startdomain`

## Make Instance
`streamtool mkinstance --numresources 1`

## Start Instance
`streamtool startinstance`

## Add Resource Spec to Instance
`streamtool addresourcespec --numresources 1`

## State of Mesos Resource Manager
`./streams-on-mesos getresourcestate`
<pre>
Display Name      Native Name  Mesos Task ID         Resource State  Request State  Completion Status  Host Name      Is Master  
mesos_resource_0  resource_0   streams_resource_0_2  RUNNING         ALLOCATED      NONE               172.31.29.41   true       
mesos_resource_2  resource_2   streams_resource_2_0  RUNNING         ALLOCATED      NONE               172.31.25.121  false      
mesos_resource_1  resource_1   streams_resource_1_0  RUNNING         ALLOCATED      NONE               172.31.39.232  false
</pre>

`./streams-on-mesos getresourcestate -l`
<pre>
Display Name      Native Name  Mesos Task ID         Resource State  Request State  Completion Status  Host Name      Is Master  Tags                               Cores  Memory
mesos_resource_0  resource_0   streams_resource_0_0  RUNNING         ALLOCATED      NONE               172.31.25.121  true       [jmx, audit, sws, authentication]  2.0    4096.0
mesos_resource_2  resource_2   streams_resource_2_0  RUNNING         ALLOCATED      NONE               172.31.39.232  false      [application]                      2.0    4096.0
mesos_resource_1  resource_1   streams_resource_1_0  RUNNING         ALLOCATED      NONE               172.31.29.41   false      [view, management]                 2.0    4096.0

</pre>

## Stop Mesos Resource Manager
`./streams-on-mesos stop`

# Resource Tags
The Streams Mesos Resource Manager supports two tag properties to control resource allocation sizes:

| Tag Property | Format | Default | Description |
| ------------ | ------ | ------- | ----------- |
| cores       | double | 2.0 | Number (or fractional part) of CPU Cores to allocate (0 = entire offer) |
| memory | double | 4096 | Amount of RAM (Megabytes) to allocate ( 0 = entire offer) |

>Note: The resource allocated for the Master Domain Controller uses the defaults

>WARNING: Creating a resource tag with cores or memory set to 0 will use the entire offer from Mesos.  This is not recommended, but can be useful for reserving entire hosts (minus what has already been allocated) for streams.

## Create a tag
Create a tag that requests 1 core and 1GB of memory:<br>
`streamtool mktag --description "Small 1 core and 1GB memory" --property cores=1 --property memory=1024 mesos_small`

Create a tag that requests 2 core and 2GB of memory:<br>
`streamtool mktag --description "Medium 2 core and 2GB memory" --property cores=2 --property memory=2048 mesos_medium`

Create a tag that will accept any size offer, however utilizes the entire offer **(USE WITH CAUTION)**:<br>
`streamtool mktag --description "Any size offer and use it all" --property cores=0 --property memory=0 mesos_all`


## Make the Instance 
`streamtool mkinstance --numresources 1,mesos_small`

## Add resources to an Instance
`streamtool addresourcespec --numresources 1,mesos_medium`


# Deploy Via Marathon
(Under Construction)
Marathon is a Mesos framework for long-running applications

The Marathon .json file here is just an example.  While under development the properties file deployed in the .tar.gz may not be acceptable for any environment other than the current development environment being used by the author.

## Create and save a Marathon configuration
<pre>
{
	"id": "/streams-on-mesos-curl",
	"instances": 1,
	"cpus": 1,
	"mem": 132,
	"cmd": "$(pwd)/streamsmesos/scripts/streams-on-mesos start",
	"args": null,
	"user": null,
	"env": {
		"STREAMS_INSTALL": "/opt/ibm/InfoSphere_Streams/4.2.0.0",
		"STREAMS_ZKCONNECT": "172.31.29.41:2181",
		"STREAMS_DOMAIN_ID": "StreamsDomain"
	},
	"uris": [
		"/home/brian/git/resourceManagers/mesos/com.ibm.streams.mesos/target/streams-on-mesos-0.0.1-SNAPSHOT.tar.gz"
	],
	"constraints": [
		[
			"hostname",
			"LIKE",
			"172.31.29.41"
		]
	]
}
</pre>

## Deploy on Marathon
<pre>
curl -s XPOST http://localhost:8080/v2/apps -d@marathon.json -H "Content-Type: application/json"
</pre>

# Dependencies
* Apache Maven
    * To check whether Maven is installed on your system, enter which mvn.
    * To download Maven, go to the [Apache Maven website](https://maven.apache.org/).
* InfoSphere Streams
	* Currently it needs to be compiled on a system with IBM Streams installed.
* (optional) [Apache Hadoop Version 2.7.0](https://hadoop.apache.org/) for HDFS provisioning


## Helpful Notes

If you ever need to stop an inactive framework in mesos:

	curl -XPOST http://localhost:5050/master/teardown -d 'frameworkId=2fabdf51-36b0-4951-9138-719f56ba1ae7-0000'

# LOGGING
The Streams Mesos Resource manager uses log4j via slf4j.  There is a default log4j.properties file built into the .jar file.
The sections below discuss exceptions to the logging controlled by the log4j.properties file.
 
## Java Messages
Most error messages can be controlled by using a log4j.properties file.  streams-on-mesos adds "." to the classpath so you can create a log4j.properties file where you execute the command from

A default log4j.properties is included in the .jar file for this package and can be found here:

> src/main/resource/log4j.properties

## C++ Messages
The Mesos Java API uses JNI internally and the underlying C/C++ logs messages to stderr

To prevent these messages from coming to the console (with different format than the log4j console appender you have configured) direct the stderr output of the streams-on-mesos command to a file: `streams-on-mesos start ... 2>stderr.out`

### Mesos Java API C++ Messages
The Mesos Java API that this project was built with uses JNI internally and the underlying C/C++ has logging messages.
Examples:
<pre>
    I1201 20:55:13.422004 12343 sched.cpp:330] New master detected at master@172.31.29.41:5050
    I1201 20:55:13.422276 12343 sched.cpp:341] No credentials provided. Attempting to register without authentication
    I1201 20:55:13.423632 12343 sched.cpp:743] Framework registered with a55e4e3f-a439-460e-80d6-d77d0ad66694-0024
</pre>
These can be controlled using environment variables:
>	`export MESOS_QUIET=1 // Removes them all`

>	`export MESOS_LOGGING_LEVEL=[ERROR,WARNING,INFO] // Sets the specific level`
	
### Zookeeper Client C++ Messages
There are a few messages that are produced by the zookeeper C++ client of mesos.  Examples:
<pre>
    2016-12-01 21:14:48,162:13288(0x7f7e3c293700):ZOO_INFO@log_env@726: Client environment:zookeeper.version=zookeeper C client 3.4.8
    2016-12-01 21:14:48,162:13288(0x7f7e3c293700):ZOO_INFO@log_env@730: Client environment:host.name=ip-172-31-29-41.ec2.internal
    2016-12-01 21:14:48,162:13288(0x7f7e3c293700):ZOO_INFO@log_env@737: Client environment:os.name=Linux
    2016-12-01 21:14:48,162:13288(0x7f7e3c293700):ZOO_INFO@log_env@738: Client environment:os.arch=3.10.0-327.36.3.el7.x86_64
</pre>
A solution to suppressing these messages is still being searched...do you have an answer?


# Current Limitations
## Streams Resource Manager Related
* Only handles a single Controller Client (single domain)
* Only handles a single version of Streams
* ...

## Mesos Related
* Only supports command_executor, no docker_executor support yet
* ...

# MORE TO COME ...
