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

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

/**
 * @author bmwilli
 *
 */
public class StreamsMesosScheduler implements Scheduler {

	private static final Logger LOG = LoggerFactory.getLogger(StreamsMesosScheduler.class);

	/**
	 * Framework that this scheduler communicates with The Framework is the
	 * bridge to the Streams Resource Server which receives the requests for
	 * resources
	 */
	StreamsMesosResourceManager _manager;
	StreamsMesosState _state;
	SchedulerDriver _schedulerDriver = null;
	String _lastErrorMessage = null;
	boolean _receivingOffers = true;

	/**
	 * @param streamsRM
	 */
	public StreamsMesosScheduler(StreamsMesosResourceManager manager, StreamsMesosState state) {
		super();
		_manager = manager;
		_state = state;
	}
	
	public void setSchedulerDriver(SchedulerDriver schedulerDriver) {
		_schedulerDriver = schedulerDriver;
	}
	
	public String getLastErrorMessage() {
		return _lastErrorMessage;
	}




	public boolean isReceivingOffers() {
		return _receivingOffers;
	}



	public void setReceivingOffers(boolean _receivingOffers) {
		this._receivingOffers = _receivingOffers;
	}



	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#disconnected(org.apache.mesos.SchedulerDriver)
	 */
	@Override
	public void disconnected(SchedulerDriver schedulerDriver) {
		LOG.debug("Mesos Scheduler: disconnected callback ");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.mesos.Scheduler#error(org.apache.mesos.SchedulerDriver,
	 * java.lang.String)
	 */
	@Override
	public void error(SchedulerDriver schedulerDriver, String s) {
		LOG.info("Mesos Scheduler receieved an error from Mesos: " + s);
		_lastErrorMessage = s;
		schedulerDriver.stop();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#executorLost(org.apache.mesos.SchedulerDriver,
	 * org.apache.mesos.Protos.ExecutorID, org.apache.mesos.Protos.SlaveID, int)
	 */
	@Override
	public void executorLost(SchedulerDriver schedulerDriver, ExecutorID executorID, SlaveID slaveID, int i) {
		LOG.info("Mesos Scheduler received notification of Lost executor on slave " + slaveID + ", no action taken.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.mesos.Scheduler#frameworkMessage(org.apache.mesos.
	 * SchedulerDriver, org.apache.mesos.Protos.ExecutorID,
	 * org.apache.mesos.Protos.SlaveID, byte[])
	 */
	@Override
	public void frameworkMessage(SchedulerDriver schedulerDriver, ExecutorID executorID, SlaveID slaveID,
			byte[] bytes) {
		LOG.info("Mesos Scheduler received message: " + new String(bytes) + " from " + executorID.getValue() + ", no action taken.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.mesos.Scheduler#offerRescinded(org.apache.mesos.
	 * SchedulerDriver, org.apache.mesos.Protos.OfferID)
	 */
	@Override
	public void offerRescinded(SchedulerDriver schedulerDriver, OfferID offerID) {
		LOG.info("Mesos Scheduler received notification that an offer has been rescinded: " + offerID.toString() + ", no action taken.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#registered(org.apache.mesos.SchedulerDriver,
	 * org.apache.mesos.Protos.FrameworkID, org.apache.mesos.Protos.MasterInfo)
	 */
	@Override
	public void registered(SchedulerDriver schedulerDriver, FrameworkID frameworkID, MasterInfo masterInfo) {
		LOG.info("Mesos Scheduler Registered with the Mesos Master on host: " + masterInfo.getHostname() + ":" + masterInfo.getPort());
		LOG.debug("  Framwork ID assigned: " + frameworkID);
		// Set framework ID for failover.  It is idempotent, so no harm in setting it again.
		_state.setMesosFrameworkId(frameworkID.getValue());

		_schedulerDriver = schedulerDriver;
		
		LOG.info("Streams Mesos Resource Manager ready to receive offers and launch tasks on Mesos");

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#reregistered(org.apache.mesos.SchedulerDriver,
	 * org.apache.mesos.Protos.MasterInfo)
	 */
	@Override
	public void reregistered(SchedulerDriver schedulerDriver, MasterInfo masterInfo) {
		LOG.info("Mesos Scheduler notifed of Re-Registered, no action taken.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.mesos.Scheduler#resourceOffers(org.apache.mesos.
	 * SchedulerDriver, java.util.List)
	 *
	 * Offer observations:
	 *    * Each offer in the list is from a separate agent (slave)
	 *    * If this framework has not been configured with a role defined
	 *      it will only be offered default resources (role: *).
	 *    * If this framework has been defined with a role then the offers
	 *      will include multiple resources of the same name:
	 *        e.g. name: cpu, scalar: 2, role: *
	 *             name: cpu, scalar: 2, role: myrole
	 *    * To use the offer, the task needs to be configured
	 *      to use resources of the coressponding role type
	 *        e.g. You can not be offered cpu(*): 2 and cpu(myrole): 4
	 *             and request a task with 6 cpus.
	 *             You can create a task with <= 2 cpu and role set to * 
	 *                         or a task with <= 4 cpu and role set to myrole
	 */
	@Override
	public void resourceOffers(SchedulerDriver schedulerDriver, List<Offer> offers) {
		LOG.trace("Resource Offers Made...");
		// Loop through offers, and exhaust the offer with resources we can
		// satisfy
		
		// ** NOTE: to handle custom roles, this section of code must change!!!
		for (Protos.Offer offer : offers) {
			LOG.trace("OFFER: {host:" + offer.getHostname() +
					", resourceCount: " + offer.getResourcesCount() +
					"}");
			boolean usedOffer = false;

			double offerCpus = 0;
			double offerMem = 0;			
			
			// Extract the resource info from the offer.
			// Loop through resources in this offer
			// If there are multiple cpu or memory resources, it is because they are from different roles (don't add them up)
			for (Resource r : offer.getResourcesList()) {
				if (r.getName().equals("cpus")) {
					LOG.trace("      CPU RESOURCE: {role: " + r.getRole() +
							", scalar: " + r.getScalar().getValue() + "}");
					if (r.getRole().equals(StreamsMesosConstants.MESOS_ROLE_DEFAULT)) {
						offerCpus = r.getScalar().getValue();
					} else {
						LOG.warn("Received a CPU resource offers with a role (" + r.getRole() + ") that is not equal to the default role (*), this should not happen until we allow a role to be defined for the framework.  Ignoring it.");
					}
				} else if (r.getName().equals("mem")) {
					LOG.trace("      MEM RESOURCE: {role: " + r.getRole() +
							", scalar: " + r.getScalar().getValue() + "}");
					if (r.getRole().equals(StreamsMesosConstants.MESOS_ROLE_DEFAULT)) {
						offerMem = r.getScalar().getValue();
					} else {
						LOG.warn("Received a MEM resource offers with a role (" + r.getRole() + ") that is not equal to the default role (*), this should not happen until we allow a role to be defined for the framework.  Ignoring it.");
					}
				} else {
					LOG.trace("      OTHER RESOURCE: {name: " + r.getName() +
							", role: " + r.getRole() +
							"}");
				}
			}

			// Get the list of new requests from the Framework
			List<StreamsMesosResource> newRequestList = _state.getRequestedResources();
			// Create List of Requests that we satisfied
			List<StreamsMesosResource> satisfiedRequests = new ArrayList<StreamsMesosResource>();
			
			if (newRequestList.size() > 0)
				LOG.debug("resourceOffers made and there are " + newRequestList.size() + " Resource Requests");
			
			// Not packing multiple launches into an offer at this time so that we spread
			// the resources across multiple Mesos slaves.
			// We will break the loop when an offer has been accepted and a task launched.
			// If there are still requests in the list, they can be satisfied when the offer is made again
			// minus the resources used by the first one we launch.
			for (StreamsMesosResource smr : newRequestList) {
			
				LOG.debug("Resource Request Available to compare to offer:");
				LOG.debug("smr: " + smr.toString());
				LOG.debug("offer: {cpu: " + offerCpus + ", mem: " + offerMem + ", id:" + offer.getId() + "}");
				
				// Check to ensure offer can meet this resources requirements
				// If this logic gets more complicated move to its own function
				if ((smr.getCpu() <= offerCpus) && (smr.getMemory() <= offerMem)) {
					LOG.debug("Offer meets requirements, building Task...");
					usedOffer = true;
				
					Protos.TaskInfo task = smr.buildStreamsMesosResourceTask(offer);
					LOG.info("Launching Mesos task for resource " + smr.getId() + " with task Id: " + task.getTaskId().getValue() + "...");
					launchTask(schedulerDriver, offer, task);
					LOG.debug("...Launched taskId" + task.getTaskId());

					satisfiedRequests.add(smr);
					// Tell resource manager we have satisfied the request and
					// status
					_state.taskLaunched(smr.getId());
					// Break out of the loop
					LOG.trace("Breaking out of loop since we have launched a task on this offer");
					break;

				} else {
					LOG.debug("Offer did not meet requirements, maybe the next offer will.");
				}
			} // end for each newRequest
				
			// Outside of iterator, remove the satisifed requests from the list of new ones
			_state.removeRequestedResources(satisfiedRequests);
			//newRequestList.removeAll(satisfiedRequests);
			satisfiedRequests.clear();
			
			// If offer was not used at all, decline it
			if (!usedOffer) {
				LOG.trace("Offer was not used, declining");
				schedulerDriver.declineOffer(offer.getId());
			}
		} // end for each offer
		LOG.trace("Finished handilng offers");
		
		// Check if there are any resource requests.  If not, supressOffers
		synchronized(_state) {
			if (_state.getRequestedResources().isEmpty()) {
				LOG.debug("There are no more requested resources, supress offers");
				supressOffers();
			}
		}
		
	}

	private void launchTask(SchedulerDriver schedulerDriver, Protos.Offer offer, Protos.TaskInfo task) {
		Collection<Protos.TaskInfo> tasks = new ArrayList<Protos.TaskInfo>();
		Collection<Protos.OfferID> offerIDs = new ArrayList<Protos.OfferID>();
		tasks.add(task);
		offerIDs.add(offer.getId());
		schedulerDriver.launchTasks(offerIDs, tasks);
	}
	
	public void killTask(Protos.TaskID taskId) {
		LOG.debug("Calling _schedulerDriver.killTask(" + taskId + ")");
		Protos.Status status = _schedulerDriver.killTask(taskId);
		LOG.debug("killTask returned driver status: " + status.toString());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#slaveLost(org.apache.mesos.SchedulerDriver,
	 * org.apache.mesos.Protos.SlaveID)
	 */
	@Override
	public void slaveLost(SchedulerDriver schedulerDriver, SlaveID slaveID) {
		LOG.info("Mesos Scheduler received notification of Lost slave: " + slaveID.getValue() + ", no action taken.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.mesos.Scheduler#statusUpdate(org.apache.mesos.SchedulerDriver,
	 * org.apache.mesos.Protos.TaskStatus)
	 */
	@Override
	public void statusUpdate(SchedulerDriver schedulerDriver, TaskStatus taskStatus) {
		
		LOG.debug("Mesos Task Status update: " + taskStatus.getState() + " from " + taskStatus.getTaskId().getValue());
		
		_state.taskStatusUpdate(taskStatus);

	}
	
	// Tell Mesos we do not want offers (until we revive them)
	// No reason to keep getting offers until we have a resource request
	public void supressOffers() {
		try {
			LOG.debug("Calling _schedulerDriver.supressOffers()");
			Protos.Status status = _schedulerDriver.suppressOffers();
			setReceivingOffers(false);
			LOG.debug("supressOffers returned driver status: " + status.toString());
		} catch (Exception e) {
			LOG.error("supressOffers Exception: " + e);
			e.printStackTrace();
		}
	}
	
	// Tell Mesos we we want to start receiving offers
	public void reviveOffers() {
		try {
			LOG.debug("Calling _schedulerDriver.reviveOffers()");
			Protos.Status status = _schedulerDriver.reviveOffers();
			setReceivingOffers(true);
			LOG.debug("reviveOffers returned driver status: " + status.toString());
		} catch (Exception e) {
			LOG.error("reviveOffers Exception: " + e);
			e.printStackTrace();
		}
	}
	
	public void reconcileTasks() {
		// Use implicit reconciliation so we can cancels tasks we no longer no about
		List<TaskStatus> emptyTaskList = new ArrayList<>();
		LOG.trace("  _scheduler.reconcileTasks calling _schedulerDriver.reconcileTasks(emptyTaskList) ...");
		_schedulerDriver.reconcileTasks(emptyTaskList);
	}

}
