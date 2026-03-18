import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Parser {
    public static Map<String, Device> devices = new HashMap<>();
    public static Map<String, List<String>> links = new HashMap<>();

    public static void parse(String filename) throws IOException {
        List<String> lines = new ArrayList<>();

        //this is to read the config file
        try (BufferedReader br = new BufferedReader(new FileReader(filename))){
            String line;
            while ((line = br.readLine()) != null){
                line = line.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }

        int i = 0;
        while (i < lines.size() && !lines.get(i).equalsIgnoreCase("Links")){
            String id = lines.get(i++);
            int port = Integer.parseInt(lines.get(i++));
            String ip = lines.get(i++);
            Device device = new Device(id,ip,port);

            //only for host
            if (id.equals("A")||id.equals("B")||id.equals("C")||id.equals("D")){
                String virtualIp = lines.get(i++);
                String gateway = lines.get(i++);
                device.virtualIps.add(virtualIp);
                device.gateway = gateway;
            } //only for routers
            else if (id.startsWith("R")){
                while (i < lines.size() && lines.get(i).startsWith("net")){
                    device.virtualIps.add(lines.get(i++));
                }
            }

            devices.put(id, device);
            links.put(id, new ArrayList<>());
        }
        i++;

        //reads the links
        while (i < lines.size()){
            String[] parts = lines.get(i++).split(":");
            String a = parts[0];
            String b = parts[1];
            links.get(a).add(b);
            links.get(b).add(a);
        }
    }
}
