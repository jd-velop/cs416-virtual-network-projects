
import java.util.LinkedList;
import java.util.List;

public class FrameParser {

    public List<String> parseFrame(String Frame) {
        if (!Frame.isEmpty()) {
            List<String> frameParts = new LinkedList<>();
            String[] spliced = Frame.split(":");
            frameParts.add(spliced[0]);
            frameParts.add(spliced[1]);
            frameParts.add(spliced[2]);
            frameParts.add(spliced[3]);
            frameParts.add(spliced[4]);
            StringBuilder userMsg = new StringBuilder();
            for (int i = 5; i < spliced.length; i++) {
                userMsg.append(spliced[i]);
            }
            frameParts.add(userMsg.toString());
            return frameParts;
        } else {
            List<String> emptyList = new LinkedList<>();
            emptyList.add("");
            return emptyList;
        }
    }

}
