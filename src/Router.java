
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

            Map<String, String> forwardingTable = getForwardingTable(routerId);
            System.out.println("Forwarding table: " + forwardingTable);

            Router r = new Router(routerId, forwardingTable, neighborAddresses);
            System.out.println("Router " + routerId + " running on port " + myDevice.port);

            DatagramSocket socket = new DatagramSocket(myDevice.port);
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

    // Helper method to set up forwarding table
    private static Map<String, String> getForwardingTable(String routerId) {
        Map<String, String> forwardingTable = new HashMap<>();
        if (routerId.equals("R1")) {
            forwardingTable.put("net1", "S1");       // directly connected via S1
            forwardingTable.put("net2", "R2");       // directly connected via R2
            forwardingTable.put("net3", "net2.R2");  // next-hop is R2
        } else if (routerId.equals("R2")) {
            forwardingTable.put("net2", "R1");       // directly connected via R1
            forwardingTable.put("net3", "S2");       // directly connected via S2
            forwardingTable.put("net1", "net2.R1");  // next-hop is R1
        }
        return forwardingTable;
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

        // parse the frame
        List<String> frameParts = frameParser.parseFrame(frame);

        String sourceMac = frameParts.get(0);
        String destMac = frameParts.get(1);
        String sourceIp = frameParts.get(2);
        String destinationIp = frameParts.get(3);
        String msg = frameParts.get(4);

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
