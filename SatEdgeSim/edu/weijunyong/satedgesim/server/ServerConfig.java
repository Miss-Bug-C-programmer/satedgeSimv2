package edu.weijunyong.satedgesim.server;

public class ServerConfig {
    public String host = "0.0.0.0";
    public int port = 8088;

    public String simConfigFile = "SatEdgeSim/settings/simulation_parameters.properties";
    public String applicationsFile = "SatEdgeSim/settings/applications.xml";
    public String edgeDataCentersFile = "SatEdgeSim/settings/edge_datacenters.xml";
    public String edgeDevicesFile = "SatEdgeSim/settings/edge_devices.xml";
    public String cloudFile = "SatEdgeSim/settings/cloud.xml";
    public String cloudLocationFile = "SatEdgeSim/settings/locationflie/cloud/cloud Fixed Position.csv";
    public String edgeDataCentersLocationFile = "SatEdgeSim/settings/locationflie/edge_datacenter/edge Fixed Position.csv";
    public String edgeDevicesLocationFile = "SatEdgeSim/settings/locationflie/edge_devices/mist Fixed Position.csv";

    public boolean disableCharts = true;
    public boolean forceSequential = true;
}
