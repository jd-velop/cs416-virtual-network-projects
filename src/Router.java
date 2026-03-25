
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

public class Router {

    private final String routerId;
    private Map<String, String> forwardingTable = new HashMap<>();
    private Map<String, String> neighborAddresses = new HashMap<>(); // deviceId -> ip:port
    private Map<String, DistanceVectorEntry> distanceVector = new HashMap<>();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("id improperly specified");
            return;
        }
        String routerId = args[0];

        try {
            Parser.parse("Config.txt");
            Device myDevice = Parser.devices.get(routerId);
            if (myDevice == null) {
                System.out.println("Device ID " + routerId + " not found in config file");
                return;
            }

            // Discover neighbors and forwarding table using helper methods
            Map<String, String> neighborAddresses = getNeighborAddresses(routerId);
            System.out.println("Neighbor addresses: " + neighborAddresses);

            Map<String, String> forwardingTable = setUpForwardingTable(routerId);
            System.out.println("Forwarding table: " + forwardingTable);

            Router r = new Router(routerId, forwardingTable, neighborAddresses);
            System.out.println("Router " + routerId + " running on port " + myDevice.port);

            DatagramSocket socket = new DatagramSocket(myDevice.port);

            // Send initial distance vectors to neighbors so they can learn our routes
            r.sendDistanceVectors(socket);

            while (true) {
                try {
                    r.receiveFrame(socket);
                } catch (IOException e) {
                    System.err.println("Error receiving frame: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error initializing router: " + e.getMessage());
        }
    }


    // Helper method to set up initial forwarding table based on directly connected neighbors
    private static Map<String, String> setUpForwardingTable(String routerId) {
        Map<String, String> forwardingTable = new HashMap<>();
        Device me = Parser.devices.get(routerId);

        // Add directly connected subnets to forwarding table
        for (String vIp : me.virtualIps) {
            String subnet = vIp.split("\\.")[0];
            forwardingTable.put(subnet, routerId); // directly connected
        }
        return forwardingTable;
    }

    // Helper method to discover neighbor addresses
    private static Map<String, String> getNeighborAddresses(String routerId) {
        List<String> neighborIds = Parser.links.get(routerId);
        Map<String, String> neighborAddresses = new HashMap<>();
        if (neighborIds != null) {
            for (String neighborId : neighborIds) {
                Device neighbor = Parser.devices.get(neighborId);
                if (neighbor != null) {
                    String addr = neighbor.ip + ":" + neighbor.port;
                    neighborAddresses.put(neighborId, addr);
                }
            }
        }
        return neighborAddresses;

    }

    // Helper method to send this router's distance vector table to all neighbors
    // format: "1:<routerId>:<dest1>,<cost1>,<nextHop1>;<dest2>,<cost2>,<nextHop2>;...<destN>,<costN>,<nextHopN>"
    public void sendDistanceVectors(DatagramSocket socket) {
        // Build the distance vector message
        StringBuilder message = new StringBuilder();
        message.append("1:").append(routerId).append(":");
        boolean first = true;
        for (Map.Entry<String, DistanceVectorEntry> entry : distanceVector.entrySet()) {
            if (!first) {
                message.append(";");
            }
            first = false;
            String dest = entry.getKey();
            DistanceVectorEntry dve = entry.getValue();
            message.append(dest).append(",").append(dve.cost).append(",").append(dve.nextHop);
        }
        String dvMessage = message.toString();

        // Send to each neighbor
        for (Map.Entry<String, String> neighbor : neighborAddresses.entrySet()) {
            String outAddress = neighbor.getValue();
            try {
                sendFrame(socket, dvMessage, outAddress);
                System.out.println("Sent distance vector to neighbor " + neighbor.getKey() + " at " + outAddress);
            } catch (IOException e) {
                System.err.println("Failed to send distance vector to neighbor " + neighbor.getKey() + ": " + e.getMessage());
            }
        }
    }

    private void initializeDistanceVector() {
        Device me = Parser.devices.get(routerId);

        for (String vIp : me.virtualIps) {
            String subnet = vIp.split("\\.")[0];
            distanceVector.put(subnet, new DistanceVectorEntry(0, routerId));
        }

        List<String> neighbors = Parser.links.get(routerId);

        for (String neighborId : neighbors) {
            Device neighbor = Parser.devices.get(neighborId);
            if (neighborId.startsWith("R")) {
                for (String vIp : neighbor.virtualIps) {
                    String subnet = vIp.split("\\.")[0];
                    if (!distanceVector.containsKey(subnet)) {
                        distanceVector.put(subnet, new DistanceVectorEntry(1, neighborId));
                    }
                }
            }
        }
    }

    public static class DistanceVectorEntry {

        public int cost;
        public String nextHop;

        public DistanceVectorEntry(int cost, String nextHop) {
            this.cost = cost;
            this.nextHop = nextHop;
        }
    }

    public Router(String routerId, Map<String, String> forwardingTable, Map<String, String> neighborAddresses) {
        this.routerId = routerId;
        this.forwardingTable = forwardingTable;
        this.neighborAddresses = neighborAddresses;

        initializeDistanceVector();
    }

    // Method to receive and process incoming frames
    public void receiveFrame(DatagramSocket socket) throws IOException {
        FrameParser frameParser = new FrameParser();
        byte[] buffer = new byte[1500];

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        String frame = new String(buffer).trim();

        // Check if this is a distance vector update message
        if (frame.startsWith("1:")) {
            handleDVMessage(socket, frame);
            return;
        }

        // parse the frame
        List<String> frameParts = frameParser.parseFrame(frame);

        String sourceMac = frameParts.get(1);
        String destMac = frameParts.get(2);
        String sourceIp = frameParts.get(3);
        String destinationIp = frameParts.get(4);
        String msg = frameParts.get(5);

        // print incoming frame
        printFrameInfo(sourceMac, destMac, sourceIp, destinationIp, msg);

        // extract subnet prefixes
        String srcSubnet = sourceIp.split("\\.")[0];
        String dstSubnet = destinationIp.split("\\.")[0];

        // if source and destination are on the same subnet, drop the frame
        if (srcSubnet.equals(dstSubnet)) {
            System.out.println("Same subnet (" + srcSubnet + "), dropping frame.");
            return;
        }

        // look up destination subnet in forwarding table
        String tableEntry = forwardingTable.get(dstSubnet);
        if (tableEntry == null) {
            System.out.println("No forwarding table entry for subnet " + dstSubnet + ", dropping frame.");
            return;
        }

        // rewrite MAC addresses
        String newSourceMac = routerId;
        String newDestMac;
        String exitDeviceId;

        if (tableEntry.contains(".")) {
            // next-hop entry (e.g. "net2.R2") – extract router ID after the dot
            exitDeviceId = tableEntry.split("\\.")[1];
            newDestMac = exitDeviceId;
        } else {
            // directly connected subnet – exit device is a switch/neighbor
            exitDeviceId = tableEntry;
            // destination MAC is the final host ID (e.g. "A" from "net1.A")
            newDestMac = destinationIp.split("\\.")[1];
        }

        // rebuild the frame with rewritten MACs
        String newFrame = newSourceMac + ":" + newDestMac + ":" + sourceIp + ":" + destinationIp + ":" + msg;

        // resolve exit device to real ip:port
        String outAddress = neighborAddresses.get(exitDeviceId);
        if (outAddress == null) {
            System.out.println("Cannot resolve address for device " + exitDeviceId + ", dropping frame.");
            return;
        }

        // print outgoing frame
        printForwardingInfo(newSourceMac, newDestMac, sourceIp, destinationIp, msg, outAddress);
        sendFrame(socket, newFrame, outAddress);
    }

    // Helper method to print frame info
    private void printFrameInfo(String sourceMac, String destMac, String sourceIp, String destinationIp, String msg) {
        System.out.println("\nReceived frame:");
        System.out.println("  Source MAC: " + sourceMac);
        System.out.println("  Dest MAC: " + destMac);
        System.out.println("  Source IP: " + sourceIp);
        System.out.println("  Dest IP: " + destinationIp);
        System.out.println("  Message: " + msg);
    }

    // Helper method to print forwarding info
    private void printForwardingInfo(String sourceMac, String destMac, String sourceIp, String destinationIp, String msg, String outAddress) {
        System.out.println("Forwarding frame:");
        System.out.println("  Source MAC: " + sourceMac);
        System.out.println("  Dest MAC: " + destMac);
        System.out.println("  Source IP: " + sourceIp);
        System.out.println("  Dest IP: " + destinationIp);
        System.out.println("  Message: " + msg);
        System.out.println("  Out to: " + outAddress);
    }

    // Handle an incoming distance vector message from a neighbor
    private void handleDVMessage(DatagramSocket socket, String frame) {
        // Format: "1:<senderId>:<dest1>,<cost1>,<nextHop1>;<dest2>,<cost2>,<nextHop2>;..."
        String[] parts = frame.split(":", 3);
        String senderId = parts[1];
        String dvPayload = parts.length > 2 ? parts[2] : "";

        Map<String, DistanceVectorEntry> neighborDV = parseDVMessage(dvPayload);
        System.out.println("Received DV from " + senderId + ": " + neighborDV);

        boolean changed = optimizeDistanceVector(senderId, neighborDV);

        if (changed) {
            System.out.println("Distance vector updated: " + distanceVector);
            updateForwardingTable();
            sendDistanceVectors(socket);
        }
    }

    // Parse the payload portion of a DV message into a map
    private Map<String, DistanceVectorEntry> parseDVMessage(String dvPayload) {
        Map<String, DistanceVectorEntry> receivedDV = new HashMap<>();
        if (dvPayload == null || dvPayload.isEmpty()) {
            return receivedDV;
        }

        String[] entries = dvPayload.split(";");
        for (String entry : entries) {
            String[] parts = entry.split(",");
            if (parts.length == 3) {
                String dest = parts[0];
                int cost = Integer.parseInt(parts[1]);
                String nextHop = parts[2];
                receivedDV.put(dest, new DistanceVectorEntry(cost, nextHop));
            }
        }
        return receivedDV;
    }

    // Returns true if any entry was updated
    public boolean optimizeDistanceVector(String neighborId, Map<String, DistanceVectorEntry> neighborDV) {
        boolean changed = false;

        for (Map.Entry<String, DistanceVectorEntry> entry : neighborDV.entrySet()) {
            String dest = entry.getKey();
            DistanceVectorEntry neighborEntry = entry.getValue();

            // Cost to reach dest through this neighbor = neighbor's cost + 1 (link cost)
            int costThroughNeighbor = neighborEntry.cost + 1;

            DistanceVectorEntry currentEntry = distanceVector.get(dest);

            if (currentEntry == null) {
                // New destination discovered
                distanceVector.put(dest, new DistanceVectorEntry(costThroughNeighbor, neighborId));
                changed = true;
            } else if (costThroughNeighbor < currentEntry.cost) {
                // Found a shorter path through this neighbor
                currentEntry.cost = costThroughNeighbor;
                currentEntry.nextHop = neighborId;
                changed = true;
            } else if (currentEntry.nextHop.equals(neighborId) && costThroughNeighbor != currentEntry.cost) {
                // The neighbor we currently route through changed its cost — update accordingly
                currentEntry.cost = costThroughNeighbor;
                changed = true;
            }
        }

        return changed;
    }

    // Rebuild the forwarding table from the current distance vector
    private void updateForwardingTable() {
        forwardingTable.clear();

        for (Map.Entry<String, DistanceVectorEntry> entry : distanceVector.entrySet()) {
            String subnet = entry.getKey();
            DistanceVectorEntry dve = entry.getValue();

            if (dve.cost == 0) {
                // Directly connected subnet — find the exit device (switch or router)
                String exitDevice = findExitDevice(subnet);
                if (exitDevice != null) {
                    forwardingTable.put(subnet, exitDevice);
                }
            } else {
                // Remote subnet — route through next-hop router
                forwardingTable.put(subnet, "via." + dve.nextHop);
            }
        }

        System.out.println("Updated forwarding table: " + forwardingTable);
    }

    // Find which directly connected neighbor links this router to the given subnet
    private String findExitDevice(String subnet) {
        List<String> neighbors = Parser.links.get(routerId);
        if (neighbors == null) {
            return null;
        }

        for (String neighborId : neighbors) {
            Device neighbor = Parser.devices.get(neighborId);
            if (neighbor == null) {
                continue;
            }

            // Check if this neighbor has a virtual IP on the subnet
            if (hasSubnet(neighbor, subnet)) {
                return neighborId;
            }

            // If neighbor is a switch, check devices behind the switch
            if (neighborId.startsWith("S")) {
                List<String> switchNeighbors = Parser.links.get(neighborId);
                if (switchNeighbors != null) {
                    for (String devId : switchNeighbors) {
                        if (devId.equals(routerId)) {
                            continue;
                        }
                        Device dev = Parser.devices.get(devId);
                        if (dev != null && hasSubnet(dev, subnet)) {
                            return neighborId; // exit through the switch
                        }
                    }
                }
            }
        }
        return null;
    }

    // Check whether a device has a virtual IP on the given subnet
    private boolean hasSubnet(Device device, String subnet) {
        for (String vIp : device.virtualIps) {
            if (vIp.startsWith(subnet + ".")) {
                return true;
            }
        }
        return false;
    }

    // Helper method to send frame
    public void sendFrame(DatagramSocket socket, String frame, String outPort) throws IOException {
        String[] parts = outPort.split(":");
        String ipString = parts[0];
        int portNumber = Integer.parseInt(parts[1]);

        byte[] buffer = frame.getBytes();
        InetAddress ip = InetAddress.getByName(ipString);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, ip, portNumber);
        socket.send(packet);
    }
}
