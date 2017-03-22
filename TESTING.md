# Test cases and scenarios

## Limitation
One area that has been lacking in the development of the Streams Mesos Resource Manager is automated testing.  

Hopefully this can be added in a future release cycle.

## Mnaual Tests
These are not meant to provide complete testing coverage.  
They are scenarios that need to be met in development to provide a 1.0 version of this resource manager.
Many of them would have multiple sub test cases with very different configurations and parameters

1) Create / Start Domain, Create / Start Instance
	with pre-deployed streams, default resource sizes (properties file)
	TEST PASSED
	
2) Add a resource specification to the instance.  Kind is set to FLEXIBLE
	2a) When there is resources available
		TEST PASSED
	2b) When there is not resources available, but then become available
		This will test PENDING and notifications
		TEST PASSED

3) Attempt to add resource to instance when resource manager does not have any more resources 
	(ensure graceful handling)
	TEST PASSED

4) Remove resource from instance (I assume you meant removing a resource specification;
	which I don't think takes effect until the instance stops)
	Yes - but ass mentioned does not take affect until restarted (use Quiesce)
	TEST PASSED

5) Quiesce a resource in a resource specification; 
	should be released back to the resource manager; and a replacement requested	
	Yes * Odd message that it failed, but it actually worked..need to investigate
	TEST PENDING SEE MESSAGE
	
6) If you can kill controller and restart.sh on a resource to simulate a resource 
	going away unexpectedly; we should eventually detect when ephemeral node timeout 
	occurs and release/request replacement.

7) resource manager can notify that a resource is being taken away. If you support this, we should release and request a replacement

8) change the domain.highAvailabilityCount and instance.highAvailabilityCount; should cause us to request or release management resources

9) create resource specifications with very large numbers; we do not expect that you would return resources and/or placeholders for an unreasonable amount of resources. Streams should deal with what you decide to return the best it can.

10) Streams can request resources with a different request type, synchronous, asynchronous, flexible. 
	When we are starting an instance we basically want synchronous so we know what we are dealing with.
	This means that the resources should be allocated and returned right away 
	(it is ok to return less than what is requested). 
	When adding resource specs, etc when instance 
	is already started we will request with "flexible" type. This means you can give us place holders
	and return resources when you get around to it or when they suddenly become available. 
	So if you support these types of scenarios you would want to return place holder descriptors 
	and then eventually notify that resources are available.
	
Feature Tests
-------------
This is a working list and as features are developed and tested, they will be removed from the list
- resource tags (memory and cpu)

Scenarios
=========
	
Scenario 1:
-----------

streamtool mkdomain --property domain.externalResourceManager=mesos
streamtool startdomain
streamtool mkinstance --numresources 1
streamtool startinstance
streamtool addresourcespec --numresources 1
streamtool quiesceresource mesos_smr_2

Should result in instance having 2 resources mesos_smr_1 and mesos_smr_3

Results: 
(Why did it report could not?  Everythign seemed to work
[brian@ip-172-31-29-41 Desktop]$ streamtool getinstancestate
Instance: StreamsInstance Started: yes State: RUNNING Resources: 2 (1/1 Management, 2/2 Application) Version: 4.2.0.0
Resource    Status  Schedulable Services                  Tags            ResourceSpecId[count,tags,..]
mesos_smr_1 RUNNING yes         RUNNING:app,sam,srm,view  management,view 1[1(none)[shared]]
mesos_smr_2 RUNNING yes         RUNNING:app               application     2[1(none)[shared]]
[brian@ip-172-31-29-41 Desktop]$ streamtool quiesceresource mesos_smr_2
CDISC1013W Do you want to quiesce the resources in the StreamsDomain domain? Enter "y" to continue or "n" to cancel: y
CDISC0075I The services are quiescing on the mesos_smr_2 resource in the StreamsDomain domain.
CDISA0035I Waiting for all services to stop.
CDISA0042I The app service stopped on the following resource: mesos_smr_2. The service had the following process id: 17955.
CDISA0101I Returning the mesos_smr_2 resource to the following resource manager: mesos.
--odd -> CDISC5173E IBM Streams could not process the following number of resources: 1. See the previous error messages.
[brian@ip-172-31-29-41 Desktop]$ streamtool getinstancestate
Instance: StreamsInstance Started: yes State: RUNNING Resources: 2 (1/1 Management, 2/2 Application) Version: 4.2.0.0
Resource    Status  Schedulable Services                  Tags            ResourceSpecId[count,tags,..]
mesos_smr_1 RUNNING yes         RUNNING:app,sam,srm,view  management,view 1[1(none)[shared]]
mesos_smr_3 RUNNING yes         RUNNING:app               application     2[1(none)[shared]]


Scenario 2: (over request resources) and cancel
-----------------------------------------------

streamtool startdomain
streamtool startinstance
streamtool addresoucespec --numresources 4
streamtool stopinstance

Should result in the pending resources being cancelled and no need to accept offers, instance resources stopped and released

	
-- Thanks Steve Halverson