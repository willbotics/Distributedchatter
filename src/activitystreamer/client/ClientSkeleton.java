package activitystreamer.client;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import activitystreamer.util.Settings;

public class ClientSkeleton extends Thread {
	private static final Logger log = LogManager.getLogger();
	private static ClientSkeleton clientSolution;
	private TextFrame textFrame;
	private int activityCounter;
	private HashMap<Integer, JSONObject> activityBuffer;
	private Long startTime;// Local time when server first started
	private Long remoteStartTime;// Clock server's local time when server first started
	// regularizedCurrentTime = currentTime - startTime + remoteStartTime
	// offset = remoteStartTime -startTime
	// regularizedCurrentTime = currentTime + offset
	private Long offset = null;
	private final int hashCode;

	//Peng: Add client socket, data streams and JSON parser
	private Socket socket;
	DataInputStream inStream;
	DataOutputStream outStream;
	BufferedReader inReader;
	JSONParser parser;

	//Peng: Add client information
	String username;
	String secret;

	
	public static ClientSkeleton getInstance(){
		if(clientSolution==null){
			clientSolution = new ClientSkeleton();
		}
		return clientSolution;
	}
	
	public ClientSkeleton(){

		textFrame = new TextFrame();
		activityCounter = 0;
		activityBuffer = new HashMap<>();
		sychronizeLocalClock();
		//Peng: Initialize socket, data streams and JSON parser
		try {
			socket = new Socket(Settings.getRemoteHostname(), Settings.getRemotePort());
			inStream = new DataInputStream(socket.getInputStream());
			outStream = new DataOutputStream(socket.getOutputStream());
			inReader = new BufferedReader(new InputStreamReader(inStream));
			parser = new JSONParser();
		} catch (IOException e) {
		    log.error("Failed to create socket or initialize data streams", e);
		}

		//Peng: Set up client information
		username = Settings.getUsername();
		secret = Settings.getSecret();

		hashCode = this.hashCode();
		//Peng: Setting up data streams.
		log.info("Client ID: " + hashCode);
		start();
	}


	@SuppressWarnings("unchecked")
	public void sendActivityObject(JSONObject activityObj){
		try {
			//Peng: Added "\n" to the end to unblock inreader.readLine() in Connection.java
			outStream.writeUTF(activityObj.toJSONString()+"\n");
			outStream.flush();
			log.debug("Message sent: " + activityObj.toJSONString());
		} catch (IOException e) {
			log.error(e);
		}
	}

	public void resendActivityObject(Integer serial){
		JSONObject activityObj = activityBuffer.get(serial);

		if(activityObj == null) {
			log.error("Activity #" + serial + " does not exist!");
		} else {
		    sendActivityObject(activityObj);
		}
	}

	public void sychronizeLocalClock() {
	    // Initialize connection to clock server, and send time request to it.
		JSONObject outJSON = new JSONObject();
		outJSON.put("command", "GET_TIME");
		try {
			socket = new Socket(Settings.getClockHostName(), Settings.getClockPort());
			inStream = new DataInputStream(socket.getInputStream());
			outStream = new DataOutputStream(socket.getOutputStream());
			inReader = new BufferedReader(new InputStreamReader(inStream));
			parser = new JSONParser();
		} catch (IOException e) {
			log.error("Failed to create socket or initialize data streams", e);
		}
		sendActivityObject(outJSON);

		// Wait for clock server response, set
		String serverMsg;
		try {
			while((serverMsg = inReader.readLine()) != null)
			{
				serverMsg = serverMsg.trim();
				JSONObject serverResponse = (JSONObject) parser.parse(serverMsg);
				String time = (String)serverResponse.get("time");
				remoteStartTime = Long.parseLong(time);
				startTime = System.currentTimeMillis();
				offset = remoteStartTime - startTime;
				log.info("Synchronized with clock with offset: " + offset);
				log.debug("Reiceived from server: " + serverResponse.toJSONString());
			}
		} catch (SocketException e) {
		    log.error(e);
        } catch (IOException e) {
		    log.error(e);
		} catch (ParseException e) {
		    log.error(e);
		}
	}

	public Long getRegularizedCurrentTime(){
		if(remoteStartTime == null) {
			log.error("Regularized time unavailable before local server clock synchronization.");
			return null;
		}

		Long currentTime = System.currentTimeMillis();
		return currentTime - startTime + remoteStartTime;
	}

	// Only used when a message is first created.
	public JSONObject preprocess(JSONObject activityObj) {
		String command = (String)activityObj.get("command");
		if(command != null && command.equals("ACTIVITY_MESSAGE")){
			activityObj.put("serial", Integer.toString(hashCode + activityCounter));
			activityCounter++;
			activityObj.put("timestamp", Long.toString(System.currentTimeMillis()+offset));
			activityObj.put("id", Integer.toString(hashCode));
			activityBuffer.put(activityCounter, activityObj);
		}

		return activityObj;
	}


	
	public void disconnect(){
	    //Peng: close socket
		try {
			socket.close();
		} catch (IOException e) {
		    log.error("Failed to close socket!");
		}

	}

	//Peng: redirects this client to (hostName, port)
	public void redirect(String hostName, int port){
		try {
			socket = new Socket(hostName, port);
			inStream = new DataInputStream(socket.getInputStream());
			outStream = new DataOutputStream(socket.getOutputStream());
			inReader = new BufferedReader(new InputStreamReader(inStream));
		} catch (IOException e) {
			log.error("Failed to create socket or initialize data streams", e);
		}
		//FIXME: might be the cause of double login message.
		//login();
	}

	//Peng: login to server
	public void login(){
	    JSONObject loginRequest = new JSONObject();
	    loginRequest.put("command", "LOGIN");

		loginRequest.put("username", username);
	    if(!username.equals("anonymous"))
            loginRequest.put("secret", secret);
	    sendActivityObject(loginRequest);
    }


	public void run(){
		//Peng: login to server on thread starting.
		login();
		//Peng: Implemented a ever-running loop to receive data from server
		String serverMsg = null;
		String hostName = null;
		int port = 0;
		try {
			while((serverMsg = inReader.readLine()) != null)
			{
				serverMsg = serverMsg.trim();
				JSONObject serverResponse = (JSONObject) parser.parse(serverMsg);
				log.debug("Reiceived from server: " + serverResponse.toJSONString());
				textFrame.setOutputText(serverResponse);

				//Peng: parse and process server commands.
				String command = (String) serverResponse.get("command");

				if (command == null)
					break;

				switch(command) {
                    case "REDIRECT":
                    	log.debug("Switch to REDIRECT");
                    	hostName = (String)serverResponse.get("hostname");
                    	port =  Integer.parseInt((String)serverResponse.get("port"));
						redirect(hostName, port);
						run();
                    	break;
                    default:
                    	break;
				}
			}
		} catch (SocketException e) {
		    log.info("Redirecting to host: " + hostName + " port: " + port);
		} catch (IOException e) {
			log.error(e);
			e.printStackTrace();
		} catch (ParseException e) {
			log.error(e);
			e.printStackTrace();
		}
	}


}
