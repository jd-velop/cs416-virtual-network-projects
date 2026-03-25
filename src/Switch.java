import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;

public class Switch {
    private List<String> Ports;
    Map<String, String> switchTable = new HashMap<>();
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("id improperly specified");
            return;
        }
        String switchID = args[0];
        try {   // check that such a switch id exists in config file
            Parser.parse("Config.txt");
            Device myDevice = Parser.devices.get(switchID);
            if (myDevice == null) {
                System.out.println("Device ID " + switchID + "not found in config file");
                return;
            }

            // learn neighbors (IP, port) using parser
            List<String> neighborIDs = Parser.links.get(switchID);
            List<String> neighborPorts = new LinkedList<>();
            if (neighborIDs != null) {
                for (String neighborID : neighborIDs) {
                    Device neighbor = Parser.devices.get(neighborID);
                    if (neighbor != null) {
                        neighborPorts.add(neighbor.ip + ":" + neighbor.port);
                    }
                }
            }

            Switch vs = new Switch(neighborPorts);
            System.out.println("Switch " + switchID + " running on port " + myDevice.port);

            // Create socket once, outside the loop
            DatagramSocket socket = new DatagramSocket(myDevice.port);
            while (true) {
                try {
                    vs.receiveFrame(socket);
                } catch (Exception e) {
                    System.err.println("Error receiving frame: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Switch(List<String> Ports) {
        this.Ports = Ports;
    }
    //Frame arrives at switch and then:
    //1. The switch uses destination MAC address in frame header to search table
    //2. If found, forward frame out the associated port
    //3. else, flood frame out all ports except the one it arrived on

    public void receiveFrame(DatagramSocket socket) throws IOException{
        FrameParser fp = new FrameParser();
        byte[] buffer = new byte[1500]; // max Ethernet frame size

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        String Frame = new String(buffer).trim();

        // Get the sender's address from the packet
        String senderAddress = packet.getAddress().getHostAddress() + ":" + packet.getPort();

        // Packet will be <sourceMAC>:<destMAC>:<msg>
        List<String> frameParts = fp.parseFrame(Frame);
        String sourceMAC = frameParts.get(0);
        String destMAC = frameParts.get(1);

        boolean sourceInTable = switchTable.containsKey(sourceMAC);
        if (!sourceInTable){
            // sourceMAC not found, we now learn the correct port (sender's address)
            switchTable.put(sourceMAC, senderAddress);
            System.out.println(switchTable.toString());
        }

        boolean destInTable = switchTable.containsKey(destMAC);
        if (!destInTable){
            flood(socket, Frame, senderAddress);
            System.out.println("Switch is flooding!");
        } else {
            String outPort = switchTable.get(destMAC);
            sendFrame(socket, Frame, outPort);
        }
    }

    public void sendFrame(DatagramSocket socket, String Frame, String outPort) throws IOException{

        String[] parts = outPort.split(":");
        String ipString = parts[0];
        int portNumber = Integer.parseInt(parts[1]);


        byte[] buffer = Frame.getBytes();
        InetAddress ip = InetAddress.getByName(ipString);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, ip, portNumber);
        socket.send(packet);

    }

    public void flood(DatagramSocket socket, String Frame, String ignorePort){
        List<String> outgoingPorts = new ArrayList<>(this.Ports);
        outgoingPorts.remove(ignorePort);
        for (String port: outgoingPorts){
            try{
                sendFrame(socket, Frame, port);
            } catch (Exception e) {
                System.out.println("Error" + e);
            }
        }
    }
}