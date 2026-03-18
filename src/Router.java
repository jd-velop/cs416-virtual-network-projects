
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

public class Router {

    private final String routerId;
    private Map<String, String> forwardingTable = new HashMap<>();
    private Map<String, String> neighborAddresses = new HashMap<>(); // deviceId -> ip:port

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

            // learn neighbors (IP, port) using parser
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

            // Hard-coded forwarding table per router
            // Format: subnet -> neighbor device ID (directly connected) or "subnet.RouterID" (next-hop)
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

            System.out.println("Forwarding table: " + forwardingTable);

            Router r = new Router(routerId, forwardingTable, neighborAddresses);
            System.out.println("Router " + routerId + " running on port " + myDevice.port);

            DatagramSocket socket = new DatagramSocket(myDevice.port);
            while (true) {
                try {
                    r.receiveFrame(socket);
                } catch (Exception e) {
                    System.err.println("Error receiving frame: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Router(String routerId, Map<String, String> forwardingTable, Map<String, String> neighborAddresses) {
        this.routerId = routerId;
        this.forwardingTable = forwardingTable;
        this.neighborAddresses = neighborAddresses;
    }

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
        System.out.println("\nReceived frame:");
        System.out.println("  Source MAC: " + sourceMac);
        System.out.println("  Dest MAC: " + destMac);
        System.out.println("  Source IP: " + sourceIp);
        System.out.println("  Dest IP: " + destinationIp);
        System.out.println("  Message: " + msg);

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
        System.out.println("Forwarding frame:");
        System.out.println("  Source MAC: " + newSourceMac);
        System.out.println("  Dest MAC: " + newDestMac);
        System.out.println("  Source IP: " + sourceIp);
        System.out.println("  Dest IP: " + destinationIp);
        System.out.println("  Message: " + msg);
        System.out.println("  Out to: " + outAddress);

        sendFrame(socket, newFrame, outAddress);
    }

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
