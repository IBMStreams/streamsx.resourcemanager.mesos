# Release Plans
* 0.5 - Initial Functional Release (no failover or high availability)
* 1.0 - Complete Functional Release (failover and high availability)
* 1.x - Placement Support (Use Mesos roles and static resources with Streams tags)

# 0.5 Release (no failover or high availability):
* DONE - <CTRL-C> when running program in foreground
	* Solution: Run in background from streams-on-mesos
* DONE - Handle situation when framework killed but zookeeper thinks domain is running
	* streamtool stopdomain --force will try and release resource
	* Solution: Request to release resource that does not exist should log message and exit gracefully
* DONE - Supress Mesos Offers when there are no outstanding resource requests
	* Only when we have a resource request that needs resources do we tell Mesos to send us offers
* DONE -Streams Resource Manager custom commands to get internal state of allResources list, etc.

* Running a second time to quickly after stop sometimes generates a mesos log error:
	I0314 20:31:03.725565   425 master.cpp:2414] Refusing subscription of framework 'IBMStreams' at scheduler-ddc5b371-cce3-4588-a5ea-ec6900974bf7@172.31.29.41:45318: Framework has been removed
	* and our log reports:
		I0314 20:31:03.726305  6859 sched.cpp:1171] Got error 'Framework has been removed'
		I0314 20:31:03.726320  6859 sched.cpp:2021] Asked to abort the driver
		[INFO ] 20:31:03 Mesos Scheduler receieved an error from Mesos: Framework has been removed
		I0314 20:31:03.726753  6859 sched.cpp:1987] Asked to stop the driver
		I0314 20:31:03.726825  6859 sched.cpp:1217] Aborting framework 'd59494dc-6c95-4f93-bb1c-9ac56924c67d-0007'
		I0314 20:31:03.726848  6859 sched.cpp:1187] Stopping framework 'd59494dc-6c95-4f93-bb1c-9ac56924c67d-0007'
	* Hard to reproduce
	
* Run on framework on Marathon

* Test that when we combine resource offers and add them up but what about if no single
	offer is as big as the resource we are requesting?


# 1.0 Release (failover and high availability:
* Handle Scheduler/Framework Failover
	* Leader Election and Framework ID reuse - DONE
	* Restore/Validate State
	* Reconcile with Mesos
* Handle Mesos Master Failover
    * Handle call to reregistered() on the scheduler	
* Multiple copies of resource manager running and stop command issued
	* Need to handle error, hard to reproduce
	* Add support for --hosts in streams-on-mesos python script to stop individual hosts on command line
	* Should it allow more than one to run on one host?
	* Talked to Steve H.  Agrees that --hosts should allow host[:port] to stop if multiple on same host
	* for now it stops both
* Run using restart.sh like streams-on-symphony with option of running in foreground
* Prevent Shutdown while resources are allocated unless --force used
* Timout waiting for task status changes
    * If a task is waiting to be running or terminal state for more than X amount of time
      then reconcile tasks to see if a message was lost from Mesos
      If not, then re-execute the task
* Utilize resource requirements in the requests to limit the offers from Mesos to what we need
	* Cuts down on chatter and offers from Mesos
* Handle multiple multiple clients (e.g. multiple domains)
* Instructions and test for PKI authentication
* Marathon submission of framework
  * run streams-on-mesos from marathon
* Web interface to get internal state
* Persistent State manager - see Symphony Resource Manager
	* ValidateState
* High Availability
	* Mesos - Handle Master failover, Reconcile Tasks, Handle Scheduler failover
	* Streams - Do the same
* Convert exceptions to ResourceManagerMessageException, did not see the API for that
* Enhance Exceptions to use Locale (see Symphony)
* Handle rolling upgrade scenarios and starting isntances at different versions.  Streams version is part of request for resources.  Generate multiple versions of resource packages to send out
* Resource packing when offers have more resources that needed (see Building applications on mesos book)
  * Do not recommend doing this becuase will overload a single host perhaps
* deploy option for HDFS
  * Need test on HDFS
* Validate Stop
  * Prevent shutdown (unless --force) if we have resources being used

## Failover logic
	if !reconciling
		reconciling = true
		suspend offers
		_state.restore()
		if resource.state == STOPPED, FAILED, CANCELLED then DELETE
		if requestState == CANCELLED, RELEASED then DELETE
		else add to reconcileingTasks list
		driver.reconcileTasks()  // implicit
		while (not exceed max time && still tasks in reconcilingTasks list)
			delay(30)
			add to total time
		end while
		Any leftover tasks in reconcilingTakss the revoke them
	end if

	StatusUpdate()
		if reconciling
			if not in reconcilingTasks
				// we do not know about it
					Stop or kill it
			else
				remove from reconcilingTakss
				Update timestamp
				Do what about the status?  Normal processing if it is a change?
			
# 1.x - Future
* Handle multiple clients/domains, currently only handles single domain
* Support and test Mesos roles and resources with Streams tags 
	* Example Scenario: Need Operator X running on Host Y because it listens on a specific address:port known by external services


# ISSUES:
## Tasks that Fail quickly, however pre-maturely telling streams resource allocated
* Implemented WAIT_ALLOCATED_SECS default of 2 to wait for a task to be running for that long before notifying streams
	* This prevents notification of allocated and then revoking resource if it fails quickly
	* this is the kind of failure that occurs if you have pre-installed streams, however, one of the slaves is not valid
	  and the start of the controller fails.  The mesos task reports running for a brief amount of time then reports failed
	* By waiting for it to be running for 2 seconds if it fails within that time, we simply try again when a new offer 
	  is available.  In testing it could take many, many attempts, but it usually finally gets an offer from a good slave
	  
## Large timeout for Streams seems to cause really slow reaction to things like:
	* notify of revoke
	* <wait>
	* Requests release
	* <wait>
	* Streamtool exits that not enough resources could be allocated
	
	* Mitigation: Add some delay between when task goes RUNNING and notifying streams
	* Why? Mesos Command Executor returns running when shell sharts running, but if Streams is going to fail
	* it will happen rather quickly in the case of pre-installed software, or it will take a long time if deploying
	* if it happens quickly we can retry and thus avoid telling Streams we have allocated the resource

## IBM Internal Classes
* In order to serialize (for storage in persistence) we are utilizing a few internal IBM classes found in the
  .jar files.  These include:
	* Casting ClientInfo -> ClientInformation and using the toJson to serialize

## Concerns with work-around
* Using mesos CommandExecutor not as fine grained as if we wrote our own executor
  - It may report running while command is still running streamsresourcesetup.sh and controller is not yet started
  - Only way to get around this may be to implement our own executor that does not report running until it is
  - Work around: Like YARN resource manager set controller.startTimeout=300

## Minor error message issue
* Streams system/impl/bin/startDomainController.sh has a which java before running dependency checker
  - On compute nodes without their own java, this reports error and does not run dependency checker
  - Need to report to IBM
  - Minor becuase later in the script it runs the domain controller using Streams internal java install
  
  		

## Development issues:
* Started using the YARN resource manager as a model where only handled specified domain and zookeeper
* Moving toward Symphony approach to support multiple
* Code in transition (example allocateResources, get domainID from clientInfo or argument?)
	* For now do both and look for inconsistencies
	* Future move to just cilentID and remove domainId as a required argument


	


## Future 1.0 Release




# Command Examples and Notes
## Resource Specification Commands
### Show resource specifications
streamtool lsresources -l
### Remove resource specification
streamtool rmresourcespec 2
### Add resource specification
streamtool addresourcespec --numresources 1
streamtool addresourcespec --numresources 1[exclusive]
streamtool addresourcespec --numresources 1,ingest
streamtool addresourcespec --numresources 1,ingest[exclusive]


