package scheduling.component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager.Priority;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.job.Break;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl.Builder;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.core.util.UnassignedJobReasonTracker;
import com.graphhopper.jsprit.core.util.VehicleRoutingTransportCostsMatrix;

import scheduling.beans.GeoPoint;
import scheduling.beans.Service;
import scheduling.beans.ServiceOrderConstraint;
import scheduling.beans.ServiceVehicleConstraint;
import scheduling.beans.Vehicle;
import scheduling.connector.RoutingConnector;
import scheduling.exceptions.InternalSchedulingErrorException;
import scheduling.exceptions.RoutingNotFoundException;

@Component
public class NursePlanner {
	
	@Autowired
	private final RoutingConnector routingConnector;
	
	private final Logger log;
	
	public NursePlanner() {
	    this.log = LoggerFactory.getLogger(this.getClass());
		this.routingConnector = new RoutingConnector();
	}
	
	public org.json.simple.JSONObject startPlanningCare(JSONObject jsonObject) throws RoutingNotFoundException, InternalSchedulingErrorException {
		
		if(jsonObject == null 
				|| jsonObject.isNull("startLocation")
				|| jsonObject.isNull("vehicles")
				|| jsonObject.isNull("services")) {
			throw new InternalSchedulingErrorException("JSON input is invalid, no scheduling possible for care scenario!");
		}
		
		// extract start location
		GeoPoint startFromCompany = null;
		if(jsonObject.has("startLocation")) {
			JSONObject location = jsonObject.getJSONObject("startLocation");
			double latitude = location.has("latitude") ? location.getDouble("latitude") : 0.0;
			double longitude = location.has("longitude") ? location.getDouble("longitude") : 0.0;
			startFromCompany = new GeoPoint(latitude, longitude);
		}
		
		// extract job constraints
		List<ServiceOrderConstraint> serviceOrderConstraints = null;
		List<ServiceVehicleConstraint> serviceVehicleConstraint = null;
		if(jsonObject.has("constraints")) {
			serviceOrderConstraints = Lists.newLinkedList();
			serviceVehicleConstraint = Lists.newLinkedList();
			JSONArray jsonArray = jsonObject.getJSONArray("constraints");
			for(int index = 0; index < jsonArray.length(); index++) {
				JSONObject constraintJSON = jsonArray.getJSONObject(index);
				// extract job order constraint
				String beforeJobId = constraintJSON.has("beforeServiceID") ? constraintJSON.getString("beforeServiceID") : "";
				String afterJobId = constraintJSON.has("afterServiceID") ? constraintJSON.getString("afterServiceID") : "";
				if (!beforeJobId.equals("") && !afterJobId.equals(""))
					serviceOrderConstraints.add(new ServiceOrderConstraint(beforeJobId, afterJobId));
				
				// extract job vehicle constraints
				String serviceId = constraintJSON.has("serviceID") ? constraintJSON.getString("serviceID") : "";
				String vehicleId = constraintJSON.has("vehicleID") ? constraintJSON.getString("vehicleID") : "";
				if (!vehicleId.equals("") && !serviceId.equals(""))
					serviceVehicleConstraint.add(new ServiceVehicleConstraint(serviceId, vehicleId));
			}
		}
		final List<ServiceOrderConstraint> serviceOrderConstraintsFinal = serviceOrderConstraints;
		final List<ServiceVehicleConstraint> serviceVehicleConstraintsFinal = serviceVehicleConstraint;
		
		Set<String> vehicleIdSet = Sets.newHashSet();
		Set<String> serviceIdSet = Sets.newHashSet();
		
		// extract vehicles
		List<Vehicle> vehicles = null;
		if(jsonObject.has("vehicles")) {
			vehicles = Lists.newLinkedList();
			JSONArray jsonArray = jsonObject.getJSONArray("vehicles");
			for(int index = 0; index < jsonArray.length(); index++) {
				List<String> skills = Lists.newLinkedList();
				JSONObject vehicleJSON = jsonArray.getJSONObject(index);
				String vehicleID = vehicleJSON.has("vehicleID") ? vehicleJSON.getString("vehicleID") : "";
				vehicleIdSet.add(vehicleID);
				int earliestStart = vehicleJSON.has("earliestStart") ? vehicleJSON.getInt("earliestStart") : 0;
				int latestArrival = vehicleJSON.has("latestArrival") ? vehicleJSON.getInt("latestArrival") : 0;
				int breakStartWindow = 0;
				int breakEndWindow = 0;
				int breakTime = 0;
				if(vehicleJSON.has("skills")) {
					JSONArray skillArray = vehicleJSON.getJSONArray("skills");
					for(int skillIndex = 0; skillIndex < skillArray.length(); skillIndex++) {
						skills.add(skillArray.getString(skillIndex));
					}
				}
				if(vehicleJSON.has("break")) {
					JSONObject breakJSON = vehicleJSON.getJSONObject("break");
					breakStartWindow = breakJSON.has("startWindow") ? breakJSON.getInt("startWindow") : 0;
					breakEndWindow = breakJSON.has("endWindow") ? breakJSON.getInt("endWindow") : 0;
					breakTime = breakJSON.has("breakTime") ? breakJSON.getInt("breakTime") : 0;
				}
				vehicles.add(new Vehicle(vehicleID, skills, earliestStart, 
						latestArrival, breakStartWindow, breakEndWindow, breakTime));
			}
		}
		
		// extract services
		List<Service> services = Lists.newLinkedList();
		if(jsonObject.has("services")) {
			JSONArray jsonArray = jsonObject.getJSONArray("services");
			services.addAll(createServiceList(jsonArray, serviceIdSet));
		}
		
		// extract blacklist
		List<ServiceVehicleConstraint> blacklist = null;
		if(jsonObject.has("blacklist")) {
			blacklist = Lists.newLinkedList();
			JSONArray jsonArray = jsonObject.getJSONArray("blacklist");
			for(int index = 0; index < jsonArray.length(); index++) {
				JSONObject blacklistItemJSON = jsonArray.getJSONObject(index);
				String vehicleId = blacklistItemJSON.has("vehicleID") ? blacklistItemJSON.getString("vehicleID") : "";
				String serviceId = blacklistItemJSON.has("serviceID") ? blacklistItemJSON.getString("serviceID") : "";
				if(serviceIdSet.contains(serviceId) && vehicleIdSet.contains(vehicleId)) {
					blacklist.add(new ServiceVehicleConstraint(vehicleId, serviceId));
				}
			}
		}
		
		// update services and vehicles
		if(blacklist != null && blacklist.size() > 0) {
			
			for(Service service: services) {
				for(ServiceVehicleConstraint blacklistItem: blacklist) {
					if(service.getServiceID().equals(blacklistItem.getServiceId())) {
						List<String> requiredSkills = service.getRequiredSkills();
						requiredSkills = updateSkills(requiredSkills, blacklistItem.getServiceId());
						service.setRequiredSkills(requiredSkills);
					}
				}
			}
			
			for(Vehicle vehicle: vehicles) {
				for(ServiceVehicleConstraint blacklistItem: blacklist) {
					if(!vehicle.getVehicleID().equals(blacklistItem.getVehicleId())) {
						List<String> skills = vehicle.getSkills();
						skills = updateSkills(skills, blacklistItem.getServiceId());
						vehicle.setSkills(skills);
					}
				}
			}
			
		}

		// prepare one list with all services for cost matrix
		List<Service> allServices = Lists.newLinkedList();
		allServices.addAll(services);
		
		// extract services already planned
		Map<String, List<Service>> currentPlanningMap = Maps.newHashMap();
        if(jsonObject.has("currentPlanning")) {
        	JSONObject currentPlanning = jsonObject.getJSONObject("currentPlanning");
    		for (Vehicle vehicle : vehicles) {
    			if (currentPlanning.has(vehicle.getVehicleID())) {
		        	JSONArray plannedJobs = currentPlanning.getJSONArray(vehicle.getVehicleID());
		        	List<Service> plannedServices = createServiceList(plannedJobs, serviceIdSet);
		        	currentPlanningMap.put(vehicle.getVehicleID(), plannedServices);
		    		allServices.addAll(plannedServices);
    			}
    		}
        }
		
		VehicleRoutingTransportCostsMatrix.Builder costMatrixBuilder = VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
		
		// calculate the same distance and time between the starting point and all patients
    	for(Service service: allServices) {
    		
    		double travelDistance = 0.0;
    		int travelTime = 0;
			try {
				travelDistance = routingConnector.getTravelDistance(startFromCompany, service.getLocation());
				travelTime = routingConnector.getTravelTime(startFromCompany, service.getLocation()) / 1000;
			} catch (Exception e) {
				throw new RoutingNotFoundException("Routing calculation from " 
						+ startFromCompany
						+ " to service "
						+ service.getServiceID()
						+ " not possible!");
			}

        	for(Vehicle vehicle: vehicles) {
        		costMatrixBuilder.addTransportDistance(vehicle.getVehicleID(), service.getServiceID(), travelDistance);
        		costMatrixBuilder.addTransportTime(vehicle.getVehicleID(), service.getServiceID(), travelTime);
        	}

    	}
    	
        for(int i = 0; i < allServices.size(); i++) {
        	
        	for(int j = i + 1; j < allServices.size(); j++) {
        		
        		double travelDistance = 0.0;
        		int travelTime = 0;
    			try {
    				travelDistance = routingConnector.getTravelDistance(allServices.get(i).getLocation(), allServices.get(j).getLocation());
    				travelTime = routingConnector.getTravelTime(allServices.get(i).getLocation(), allServices.get(j).getLocation()) / 1000;
    			} catch (Exception e) {
    				throw new RoutingNotFoundException("Routing calculation between " 
    							+ allServices.get(i).getServiceID() 
    							+ " and "
    							+ allServices.get(j).getServiceID()
    							+ " not possible!");
    			}
        		
        		costMatrixBuilder.addTransportDistance(allServices.get(i).getServiceID(), 
        				allServices.get(j).getServiceID(), travelDistance);
        		costMatrixBuilder.addTransportTime(allServices.get(i).getServiceID(), 
        				allServices.get(j).getServiceID(), travelTime);	
        	}
        	
        }
        
        for(int i = 0; i < vehicles.size(); i++) {
        	
        	for(int j = i + 1; j < vehicles.size(); j++) {
        		costMatrixBuilder.addTransportDistance(vehicles.get(i).getVehicleID(), 
        				vehicles.get(j).getVehicleID(), 0);
        		costMatrixBuilder.addTransportTime(vehicles.get(i).getVehicleID(), 
        				vehicles.get(j).getVehicleID(), 0);	
        	}
        	
        }
        
        VehicleRoutingTransportCosts costMatrix = costMatrixBuilder.build();

		VehicleTypeImpl.Builder vehicleTypeBuilder = VehicleTypeImpl.Builder.newInstance("vehicleType");
		VehicleType vehicleType = vehicleTypeBuilder
				.setCostPerDistance(1)
				.build();
		
        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        vrpBuilder.setRoutingCost(costMatrix);
        
        // prepare vehicles for VRP
        for(Vehicle vehicle: vehicles) {
        	
    		Break lunchBreak = Break.Builder.newInstance("Pause " + vehicle.getVehicleID())
    				.setTimeWindow(TimeWindow.newInstance(vehicle.getBreakStartWindow(),
    						vehicle.getBreakEndWindow()))
    				.setServiceTime(vehicle.getBreakTime())
    				.setPriority(1)
    				.build();
            Builder vehicleBuilder = Builder.newInstance(vehicle.getVehicleID());
            vehicleBuilder.setStartLocation(Location.newInstance(vehicle.getVehicleID()));
            vehicleBuilder.setEndLocation(Location.newInstance(vehicle.getVehicleID()));
            vehicleBuilder.setType(vehicleType);
            for(String skill: vehicle.getSkills()) {
            	vehicleBuilder.addSkill(skill);
            }
            vehicleBuilder.setReturnToDepot(true);
            vehicleBuilder.setBreak(lunchBreak);
            vehicleBuilder.setLatestArrival(vehicle.getLatestArrival());
            vehicleBuilder.setEarliestStart(vehicle.getEarliestStart());
        	vrpBuilder.addVehicle(vehicleBuilder.build());
        	
        }
        
        // prepare new services for VRP
        for(Service service: services)
        	vrpBuilder.addJob(buildService(service));

        // prepare initial services for VRP if exist
        if (!currentPlanningMap.isEmpty()) {
        	for (com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle : vrpBuilder.getAddedVehicles()) {
				if (currentPlanningMap.containsKey(vehicle.getId())) {
	    			VehicleRoute.Builder initialRoute = VehicleRoute.Builder.newInstance(vehicle);
	    			
		        	for (Service plannedService : currentPlanningMap.get(vehicle.getId()))
		                initialRoute.addService(buildService(plannedService));
		        	
		        	vrpBuilder.addInitialVehicleRoute(initialRoute.build());
				}
	        }
        }
    	
		// create new VRP
        VehicleRoutingProblem problem = vrpBuilder.build();
        
        // add constraints for the routing problem
        StateManager stateManager = new StateManager(problem);
        ConstraintManager constraintManager = new ConstraintManager(problem, stateManager);
        
		HardActivityConstraint jobOrderActivityConstraint = new ServiceOrderActivityConstraint(serviceOrderConstraintsFinal);
		constraintManager.addConstraint(jobOrderActivityConstraint, Priority.CRITICAL);
		
		HardActivityConstraint jobVehicleActivityConstraint = new ServiceVehicleActivityConstraint(serviceVehicleConstraintsFinal);
		constraintManager.addConstraint(jobVehicleActivityConstraint, Priority.HIGH);
		
		// build the algorithm
		VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem)
	            .setStateAndConstraintManager(stateManager,constraintManager)
	            .addCoreStateAndConstraintStuff(true)
	            .buildAlgorithm();

		UnassignedJobReasonTracker reasonTracker = new UnassignedJobReasonTracker();
		algorithm.addListener(reasonTracker);
		
		// retrieve solutions and choose best
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
        VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);
        Collection<VehicleRoute> routes = bestSolution.getRoutes();
        // SolutionPrinter.print(problem, bestSolution, SolutionPrinter.Print.VERBOSE);
        
		// create map with optimized tour
		org.json.simple.JSONObject obj = new org.json.simple.JSONObject();
		Map<String, List<Service>> result = Maps.newHashMap();
        for(VehicleRoute route: routes) {
        	List<Service> jobList = Lists.newLinkedList();
        	for(TourActivity act : route.getActivities()) {
        		Job job = ((TourActivity.JobActivity) act).getJob();
				String serviceID = job.getId();
				List<String> requiredSkills = Lists.newLinkedList();
				requiredSkills.addAll(job.getRequiredSkills().values());
				GeoPoint serviceLocation = null;
				for (Service service : allServices) {
					if (service.getServiceID().equals(serviceID))
						serviceLocation = service.getLocation();
				}
				
        		jobList.add(new Service(
        				serviceID,
        				new scheduling.beans.TimeWindow(
        						Math.toIntExact(Math.round(act.getArrTime())), 
        						Math.toIntExact(Math.round(act.getEndTime()))
                				),
        				Math.toIntExact(Math.round(act.getOperationTime())),
        				serviceLocation,
        				requiredSkills
        				));
        	}
        	
        	result.put(route.getVehicle().getId(), jobList);
        }
        obj.putAll(result);
        
        // add unassigned jobs to result
        Collection<Job> unassignedJobs = bestSolution.getUnassignedJobs();
        List<org.json.simple.JSONObject> openJobs = Lists.newLinkedList();
        for (Job unassignedJob : unassignedJobs) {
        	org.json.simple.JSONObject openJob = new org.json.simple.JSONObject();
        	openJob.put("serviceID", unassignedJob.getId());
        	openJob.put("reason", reasonTracker.getMostLikelyReason(unassignedJob.getId()));
        	openJobs.add(openJob);
        }
        
        obj.put("unassigned", openJobs);
        
		return obj;
	}

	private List<Service> createServiceList(JSONArray jsonArray, Set<String> patientIdSet) {
		List<Service> services = Lists.newLinkedList();
		for(int index = 0; index < jsonArray.length(); index++) {
			List<String> requiredSkills = Lists.newLinkedList();
			JSONObject serviceJSON = jsonArray.getJSONObject(index);

			String serviceID = null;
			if (serviceJSON.has("serviceID"))
				serviceID = serviceJSON.getString("serviceID");
			else
				serviceID = "";
			
			patientIdSet.add(serviceID);
			int serviceTime = serviceJSON.has("serviceTime") ? serviceJSON.getInt("serviceTime") : 0;
			double longitude = 0.0;
			double latitude = 0.0;
			int start = 0;
			int end = 0;
			if(serviceJSON.has("requiredSkills")) {
				JSONArray skillArray = serviceJSON.getJSONArray("requiredSkills");
				for(int skillIndex = 0; skillIndex < skillArray.length(); skillIndex++) {
					requiredSkills.add(skillArray.getString(skillIndex));
				}
			}
			if(serviceJSON.has("location")) {
				JSONObject locationJSON = serviceJSON.getJSONObject("location");
				longitude = locationJSON.has("longitude") ? locationJSON.getDouble("longitude") : 0.0;
				latitude = locationJSON.has("latitude") ? locationJSON.getDouble("latitude") : 0.0;
			}
			if(serviceJSON.has("timeWindow")) {
				JSONObject timeJSON = serviceJSON.getJSONObject("timeWindow");
				start = timeJSON.has("start") ? timeJSON.getInt("start") : 0;
				end = timeJSON.has("end") ? timeJSON.getInt("end") : 0;
			
				if (serviceTime == 0) 
					serviceTime = end - start;
			}
			
			services.add(new Service(
					serviceID,
					new scheduling.beans.TimeWindow(start, end),
					serviceTime, 
					new GeoPoint(latitude, longitude),
					requiredSkills
				));
		}		
		
		return services;
	}

	private List<String> updateSkills(List<String> requiredSkills,
			String patientId) {

		boolean found = false;
		for(String skill: requiredSkills) {
			if(skill.equals(patientId)) {
				found = true;
			}
		}
		if(!found) {
			requiredSkills.add(patientId);
		}
		
		return requiredSkills;
	}

	private com.graphhopper.jsprit.core.problem.job.Service buildService(Service service) {
	
		com.graphhopper.jsprit.core.problem.job.Service.Builder serviceInstance = 
				com.graphhopper.jsprit.core.problem.job.Service.Builder.newInstance(service.getServiceID());
			
		serviceInstance.setLocation(Location.newInstance(service.getServiceID()));
			if(!service.getRequiredSkills().isEmpty()) {
				for(String skill: service.getRequiredSkills()) {
					serviceInstance.addRequiredSkill(skill);
				}
			}
		serviceInstance.addTimeWindow(service.getTimeWindow().getStart(), service.getTimeWindow().getEnd());
		serviceInstance.setServiceTime(service.getServiceTime());
		serviceInstance.setName(service.getServiceID());
    	
		return serviceInstance.build();
	}

}
 