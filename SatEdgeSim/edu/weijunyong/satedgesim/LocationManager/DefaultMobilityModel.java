package edu.weijunyong.satedgesim.LocationManager;

import edu.weijunyong.satedgesim.DataCentersManager.ServersManager;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;
import edu.weijunyong.satedgesim.Topology.TopologyPosition;
import edu.weijunyong.satedgesim.Topology.TrajectoryPositionProvider;

public class DefaultMobilityModel extends Mobility {


	public DefaultMobilityModel(Location currentLocation) {
		super(currentLocation);
	}

	public DefaultMobilityModel() { 
		super();
	}

	public Location getNextLocation(int ID, double Simulationtime, String type) {
		simulationParameters.TYPES nodeType;
		if ("cloud".equals(type)) nodeType = simulationParameters.TYPES.CLOUD;
		else if ("edge".equals(type)) nodeType = simulationParameters.TYPES.EDGE_DATACENTER;
		else nodeType = simulationParameters.TYPES.EDGE_DEVICE;
		TopologyPosition position = new TrajectoryPositionProvider().getPosition(nodeType, ID, Simulationtime);
		Double x_position = position.xMeters;
		Double y_position = position.yMeters;
		Double z_position = position.zMeters;
    	currentLocation = new Location(x_position, y_position, z_position);
    	//System.out.println("DefaultMobilityModel: "+type + FID+ " Location is: "+ x_position+","+y_position+","+z_position);
		return new Location(x_position, y_position, z_position);
	}

	public Location getCurrentLocation() {
		return this.currentLocation;
	}
}
