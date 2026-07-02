package edu.weijunyong.satedgesim.LocationManager;

import edu.weijunyong.satedgesim.DataCentersManager.ServersManager;
import edu.weijunyong.satedgesim.ScenarioManager.simulationParameters;

public class DefaultMobilityModel extends Mobility {


	public DefaultMobilityModel(Location currentLocation) {
		super(currentLocation);
	}

	public DefaultMobilityModel() { 
		super();
	}

	public Location getNextLocation(int ID, double Simulationtime, String type) {
		int time = (int)Simulationtime; //double
		//String FID = Integer.toString(ID);
		//String fileName = null;
		double[] locationPos;
		if ("cloud".equals(type)) {
			locationPos= ServersManager.Setnodelocation(simulationParameters.Cloudlocationinfo,ID,time);
		} else if ("edge".equals(type)) {
			locationPos= ServersManager.Setnodelocation(simulationParameters.EdgeDataCenterslocationinfo,ID,time);
		} else {
			//fileName = MainApplication.getLocationFolder() + "edge_devices/mist Fixed Position.csv";
			locationPos= ServersManager.Setnodelocation(simulationParameters.EdgeDeviceslocationinfo,ID,time);
		}
		
    	
    	Double x_position = locationPos[0];
    	Double y_position = locationPos[1];
    	Double z_position = locationPos[2];
    	currentLocation = new Location(x_position, y_position, z_position);
    	//System.out.println("DefaultMobilityModel: "+type + FID+ " Location is: "+ x_position+","+y_position+","+z_position);
		return new Location(x_position, y_position, z_position);
	}

	public Location getCurrentLocation() {
		return this.currentLocation;
	}
}
