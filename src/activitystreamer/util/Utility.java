package activitystreamer.util;

import org.json.simple.JSONObject;

public class Utility {

    // A utility function to retrieve a field from a JSON object, handling possible exceptions
    public static Object getField(JSONObject json, String fieldName){
        Object result = null;
        result = json.get(fieldName);

        return result;
    };
}
