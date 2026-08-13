package edu.weijunyong.satedgesim.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

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

    public void applyScenarioDirectory(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("scenario directory must not be empty");
        }
        File directory = new File(path);
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("scenario directory does not exist: " + path);
        }
        simConfigFile = required(directory, "simulation_parameters.properties");
        cloudFile = required(directory, "cloud.xml");
        edgeDevicesFile = required(directory, "edge_devices.xml");
        edgeDataCentersFile = required(directory, "edge_datacenters.xml");
        cloudLocationFile = required(directory, "locations" + File.separator + "geo.csv");
        edgeDevicesLocationFile = required(directory, "locations" + File.separator + "leo.csv");
        edgeDataCentersLocationFile = required(directory, "locations" + File.separator + "ground.csv");
        File scenarioApplications = new File(directory, "applications.xml");
        if (scenarioApplications.isFile()) {
            applicationsFile = scenarioApplications.getPath();
        }
    }

    /** Validate /reset before replacing an existing live session. */
    public int resolveAndValidateDevicesCount(int requestedDevices) {
        int trajectoryCount = countLocationBlocks(edgeDevicesLocationFile);
        int resolved = requestedDevices == -1 ? trajectoryCount : requestedDevices;
        if (resolved < 1 || resolved > trajectoryCount) {
            throw new IllegalArgumentException("devicesCount must satisfy 1 <= devicesCount <= "
                    + trajectoryCount + ", got " + resolved);
        }
        return resolved;
    }

    private int countLocationBlocks(String locationFile) {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(locationFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("\"Time (EpSec)\"")) {
                    count++;
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot read LEO trajectory file: " + locationFile, error);
        }
        if (count == 0) {
            throw new IllegalStateException("LEO trajectory file has no node blocks: " + locationFile);
        }
        return count;
    }

    private String required(File directory, String child) {
        File file = new File(directory, child);
        if (!file.isFile()) {
            throw new IllegalArgumentException("scenario directory is missing: " + file.getPath());
        }
        return file.getPath();
    }
}
