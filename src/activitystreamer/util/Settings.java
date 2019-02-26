package activitystreamer.util;

import java.math.BigInteger;
import java.net.Socket;
import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Settings {
	private static final Logger log = LogManager.getLogger();
	private static SecureRandom random = new SecureRandom();
	private static int localPort = 3780;
	private static String localHostname = "localhost";
	private static String remoteHostname = null;
	private static int remotePort = 3780;
	private static int activityInterval = 20000; // milliseconds
	private static String secret = null;
	private static String username = "anonymous";
	private static String mySecret = "bbb";
	//deadpool info
	private static String backServer = "localhost";
	private static String backSecret = null;
	private static int backPort = 3781;
	// parent info + getter and setters
	private static String parentSecret = null;
	private static String parentServer = null;
	private static int parentPort;


	public static String getParentSecret() {
		return parentSecret;
	}

	public static void setParentSecret(String parentSecret) {
		Settings.parentSecret = parentSecret;
	}

	public static String getParentServer() {
		return parentServer;
	}

	public static void setParentServer(String parentServer) {
		Settings.parentServer = parentServer;
	}

	public static int getParentPort() {
		return parentPort;
	}

	public static void setParentPort(int parentPort) {
		Settings.parentPort = parentPort;
	}

	//Peng: For clock server
	private static String clockHostName = "localhost";
	private static int clockPort = 3779;

	
	public static int getLocalPort() {
		return localPort;
	}

	public static void setLocalPort(int localPort) {
		if(localPort<0 || localPort>65535){
			log.error("supplied port "+localPort+" is out of range, using "+getLocalPort());
		} else {
			Settings.localPort = localPort;
		}
	}
	
	public static int getRemotePort() {
		return remotePort;
	}

	public static void setRemotePort(int remotePort) {
		if(remotePort<0 || remotePort>65535){
			log.error("supplied port "+remotePort+" is out of range, using "+getRemotePort());
		} else {
			Settings.remotePort = remotePort;
		}
	}
	
	public static String getRemoteHostname() {
		return remoteHostname;
	}

	public static void setRemoteHostname(String remoteHostname) {
		Settings.remoteHostname = remoteHostname;
	}
	
	public static int getActivityInterval() {
		return activityInterval;
	}

	public static void setActivityInterval(int activityInterval) {
		Settings.activityInterval = activityInterval;
	}
	
	public static String getSecret() {
		return secret;
	}

	public static void setSecret(String s) {
		secret = s;
	}
	
	public static String getUsername() {
		return username;
	}

	public static void setUsername(String username) {
		Settings.username = username;
	}
	
	public static String getLocalHostname() {
		return localHostname;
	}

	public static void setLocalHostname(String localHostname) {
		Settings.localHostname = localHostname;
	}
	public static String getMySecret() { return mySecret; }

	public static void setbackServer(String remoteHostname) {
		Settings.backServer = remoteHostname;
	}
	public static String getbackServer() { return backServer; }

	public static void setbackPort(int remotePort) {
		Settings.backPort= remotePort;
	}
	public static int getbackPort() { return backPort; }

	public static void setBackSecret(String remoteSecret) {
		Settings.backSecret = remoteSecret;
	}
	public static String getBackSecret() { return backSecret; }




	/*
	 * some general helper functions
	 */

	public static String socketAddress(Socket socket){
		return socket.getInetAddress()+":"+socket.getPort();
	}

	public static String nextSecret() {
	    return new BigInteger(130, random).toString(32);
	 }


	public static String getClockHostName() {
	    return clockHostName;
    }

    public static int getClockPort(){
	    return clockPort;
    }





	public static void setMySecret(String mySecret) {
		Settings.mySecret = mySecret;
	}

}
