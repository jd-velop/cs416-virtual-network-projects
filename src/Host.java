import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Host {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("id improperly specified");
            return;
        }
        String hostID = args[0];
        try {
            Parser.parse("Config.txt");
            Device myDevice = Parser.devices.get(hostID);
            if (myDevice == null) {
                System.out.println("Device ID " + hostID + " not found in config file");
                return;
            }
            List<String> neighborID = Parser.links.get(hostID);
            if (neighborID.isEmpty()) {
                System.out.println("No neighbor found for host " + hostID);
                return;
            }
            Device neighbor = Parser.devices.get(neighborID.get(0)); // assuming only one neighbor for host

            // create thread pool of 2 threads
            ExecutorService es = Executors.newFixedThreadPool(2);

            // Create shared socket bound to host's configured port
            DatagramSocket sharedSocket = new DatagramSocket(myDevice.port);

            // Start send and receive threads
            es.execute(new SendPacket(myDevice.id, neighbor.ip, neighbor.port, myDevice.virtualIps.toString(), myDevice.gateway, es, sharedSocket));
            es.execute(new ReceivePacket(es, hostID, sharedSocket));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static class SendPacket implements Runnable {

        private String id;
        private String ip;
        private int port;
        private String virtualIp;
        private String gateway;
        private ExecutorService es;
        private DatagramSocket socket;

        public SendPacket(String id, String ip, int port, String virtualIp, String gateway, ExecutorService es, DatagramSocket socket) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.virtualIp = virtualIp;
            this.gateway = gateway;
            this.es = es;
            this.socket = socket;
        }

        @Override
        public void run() {
            Scanner scanner = new Scanner(System.in);
            System.out.println("Welcome, Host " + id);
            while (true) {
                System.out.print("(<dest. ip>:<message>) or 'quit' to exit: ");
                String message = scanner.nextLine();
                if (message.trim().toLowerCase().equals("quit")) {
                    System.out.println("\nShutting down device\n");
                    scanner.close();
                    socket.close();
                    es.shutdownNow(); // signals other thread to stop, too.
                    break;
                } else {
                    String[] messageArray = message.split(":", 2);
                    String destinationIP = messageArray[0];
                    String sendMessage = messageArray[1];
                    String sourceMac = id;
                    String sourceIP = virtualIp.substring(1,virtualIp.length()-1);
                    String destinationMac = "";

                    //Check if destination is in the same subnet using message split
                    String[] destinationSubnet = destinationIP.split("\\.", 2);
                    String[] sourceSubnet = sourceIP.split("\\.", 2);
                    if (sourceSubnet[0].equals(destinationSubnet[0])){
                        destinationMac = destinationIP.substring(5);
                    } else {
                        destinationMac = gateway.substring(5);
                    }

                    // Debug code to check what is being sent
                    //System.out.println(sourceMac + ":" + destinationMac + ":" + sourceIP + ":" + destinationIP + ":" + sendMessage);
                    byte[] buffer = ("0:" + sourceMac + ":" + destinationMac + ":" + sourceIP + ":" + destinationIP + ":" + sendMessage).getBytes();
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(ip), port);
                        socket.send(packet);
                    } catch (IOException e) {
                        System.err.println("Error sending packet: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static class ReceivePacket implements Runnable {

        private ExecutorService es;
        private String hostID;
        private DatagramSocket socket;

        public ReceivePacket(ExecutorService es, String hostID, DatagramSocket socket) {
            this.es = es;
            this.hostID = hostID;
            this.socket = socket;

        }

        @Override
        public void run() {
            try  {
                byte[] buffer = new byte[1024];
                while (!es.isShutdown()) { // keep running until quit
                    DatagramPacket dataRequest = new DatagramPacket(buffer, buffer.length);
                    socket.receive(dataRequest);
                    String received = new String(dataRequest.getData(), 0, dataRequest.getLength());
                    String[] spliced = received.split(":");

                    //I think this is the correct way to grab the message
                    String senderIp = spliced[3];
                    // String receiverID = spliced[1];
                    String msg = spliced[5];

                    // if the packet is not for me, print "MAC address mismatch" and continue
                    if (!spliced[2].equalsIgnoreCase(hostID)) {
                        System.out.println("\nMAC address mismatch\n");
                        System.out.print("(<dest. ip>:<message>) or 'quit' to exit: ");
                        continue;
                    }

                    System.out.println("\n  - " + senderIp + ": " + msg + "\n");
                    System.out.print("(<dest. ip>:<message>) or 'quit' to exit: ");
                }
            } catch (IOException e) {
                System.out.println("Socket closed");
                //Prints error
//                System.err.println("Error receiving packet: " + e.getMessage());
//                e.printStackTrace();
            }
        }
    }


}
