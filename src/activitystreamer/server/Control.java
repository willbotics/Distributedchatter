package activitystreamer.server;

import java.io.*;
import java.net.Socket;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//required for process of json message
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import activitystreamer.util.Settings;

public class Control extends Thread {


    private static final Logger log = LogManager.getLogger();
    private static ArrayList<Connection> connections;
    private static boolean term=false;
    private static Listener listener;
    private static String serverID;
    public static boolean isroot = false;

    private Long startTime;// Local time when server first started
    private Long remoteStartTime;// Clock server's local time when server first started
    // regularizedCurrentTime = currentTime - startTime + remoteStartTime
    // offset = remoteStartTime -startTime
    // regularizedCurrentTime = currentTime + offset
    private Long offset = null;

    private HashMap<String, HashMap<Integer, JSONObject>> outMessageLog;//username, (serial, activity)
    private HashMap<Integer, HashMap<Integer, JSONObject>> inMessageLog;//Client ID, (serial, activity)
    // Connection to receiver, (id, serial)
    private HashMap<Connection, HashMap<Integer, Integer>> lastSentMessageSerial = new HashMap<>();
    // Connection to receiver, (id, activityList)
    private HashMap<Connection, HashMap<Integer, ArrayList<JSONObject>>> messageBuffer = new HashMap<>();

    protected static Control control = null;


    
    //ArrayList<String[]> accounts = new ArrayList<String[]>();
    HashMap<String, String> accounts = new HashMap<>();

    /** changed loginState to loginTime **/
    HashMap<String, Long> loginTime = new HashMap<>();
    HashMap<Connection, Long> loginTimeByConnection = new HashMap<>();
    HashMap<Connection, String> connectionToUsername = new HashMap<>();


    ArrayList<Connection> conToServers = new ArrayList<>();
    //hashmap of pending usernames, key = username, value = hashmap of server ip's and their responses.
    HashMap<String, HashMap<Connection, Boolean>> pendingAccountUsernameResponses = new HashMap<>();
    //hashmap of pending usernames, key = username, value = the connection to the client that requested the registration.
    HashMap<String, Connection> pendingAccountUsernameClientCons = new HashMap<>();

    HashMap<String, Connection> pendingAccountUsernameServerCons = new HashMap<>();

    //hashmap of pending login responses, key = username, value = hashmap of server ip's and their responses.
    HashMap<String, HashMap<Connection, Boolean>> pendingLoginResponses = new HashMap<>();

    //hashmap of pending login, key = username, value = the connection to the client that requested the registration.
    HashMap<String, Connection> pendingLoginClientCons = new HashMap<>();

    HashMap<String, Connection> pendingLoginServerCons = new HashMap<>();

    //look up table from server host name to its load
    HashMap<String, Integer> serverLoads = new HashMap<>();

    HashMap<String, Integer> serverToPort = new HashMap<>();
    //Deabpool stores the info of the server this server recconects to if its parent dies (the parent of that parent/ first server in its con list)
    //Pool of servers to connect to upon death of its parent (secret, remote hostname, remote port)




    public static Control getInstance() {
        if(control==null){
            control=new Control();
        }
        return control;
    }

    public Control() {
        // initialize the connections array
        connections = new ArrayList<Connection>();
        outMessageLog = new HashMap<>();
        // start a listener
        try {
            listener = new Listener();
        } catch (IOException e1) {
            log.fatal("failed to startup a listening thread: "+e1);
            System.exit(-1);
        }

        //String[] guestAccount = {"guest","password"};
        accounts.put("guest", "password");
        loginTime.put("anonymous", remoteStartTime);

        // assign server ID:
        serverID = Settings.getMySecret();

        start();
    }

    //modified to add a single server to conToServer list
    public void initiateConnection(){
        // make a connection to another server if remote hostname is supplied
        if(Settings.getRemoteHostname()!=null){
            try {
                Connection outCon = outgoingConnection(new Socket(Settings.getRemoteHostname(),Settings.getRemotePort())); //Start a socket w/ the cmnd line entered RH and RP
                JSONObject authenticateServerJSON = new JSONObject();// create new JSON object for AUTHENTICATION MSG
                authenticateServerJSON.put("command","AUTHENTICATE");
                authenticateServerJSON.put("secret", Settings.getSecret()); // PUT the cmnd line entered secret in the AUTH msg
                authenticateServerJSON.put("port", Integer.toString(Settings.getLocalPort()));
                authenticateServerJSON.put("hostname", Settings.getLocalHostname());
                authenticateServerJSON.put("mysecret", Settings.getMySecret());
                outCon.setSecrete(Settings.getSecret());
                // outCon is a connection to remote server
                outCon.setServer();
                //When we authenticate to a server at startup they become our parent (for failure model)
                outCon.setParent();
                Settings.setParentPort(Settings.getRemotePort());
                Settings.setParentSecret(Settings.getSecret());
                Settings.setParentServer(Settings.getRemoteHostname());
                log.info("My Parent should be: " + outCon.getSocket().getRemoteSocketAddress() +"and is" +Settings.getParentPort());
                outCon.writeMsg(authenticateServerJSON.toJSONString());
                log.info("Connected to Server: " + outCon.getSocket().getRemoteSocketAddress());
            } catch (IOException e) {
                log.error("failed to make connection to "+Settings.getRemoteHostname()+":"+Settings.getRemotePort()+" :"+e);
                System.exit(-1);
            }
        }
    }
    //Start a connection to another server we find out at runtime
    public void runtimeServer(String secret, String RemoteHostname, int RemotePort){
        try {
            log.info("runtime server called");
            Connection outCon = outgoingConnection(new Socket(RemoteHostname,RemotePort)); //Start a socket w/ prgm arguments
            JSONObject authenticateServerJSON = new JSONObject();// create new JSON object for AUTHENTICATION MSG
            authenticateServerJSON.put("command","AUTHENTICATE");
            authenticateServerJSON.put("secret", secret);
            authenticateServerJSON.put("port", Integer.toString(Settings.getLocalPort()));
            authenticateServerJSON.put("hostname", Settings.getLocalHostname());
            authenticateServerJSON.put("mysecret", Settings.getMySecret());
            // outCon is a connection to remote server
            outCon.setServer();
            //set new connection as your parent
            outCon.setParent();
            outCon.setSecrete(Settings.getSecret());
            Settings.setParentPort(RemotePort);
            Settings.setParentSecret(secret);
            Settings.setParentServer(RemoteHostname);
            log.info("My new Parent should be: " + outCon.getSocket().getRemoteSocketAddress() +"and is" +Settings.getParentPort());
            //add new connection to our connection list
            outCon.writeMsg(authenticateServerJSON.toJSONString());
            log.info("Connected to backup serverServer: " + outCon.getSocket().getRemoteSocketAddress());
        } catch (IOException e) {
            log.error("failed to make connection to back up server"+Settings.getRemoteHostname()+":"+Settings.getRemotePort()+" :"+e);
            System.exit(-1);
        }

    }


    /*
     * Processing incoming messages from the connection.
     * Return true if the connection should close.
     */
    public synchronized boolean process(Connection con,String msg){

        //Sender's IP address
        String msgIP = Settings.socketAddress(con.getSocket()).split(":")[0];
        //Justin: Sender's Port
        int msgPort = Integer.parseInt(Settings.socketAddress(con.getSocket()).split(":")[1]);


        //there are certain situations in which you may want the server to process something, but not respond
        //if that's the case, set skipResponse equal to true
        boolean skipResponse = false;


        //set up json object from msg
        JSONParser parser;
        parser = new JSONParser();
        //added a boolean flag which is used in Connection.java, indicating whether it should be terminated.
        boolean connectionTerm = true;
        // Peng: added a json object to hold return message
        JSONObject returnJSON = new JSONObject();
        JSONObject receivedJSON = null;

        //quick fix for strange sent string behaviour
        msg = msg.trim();
        if(msg.charAt(0) != '{') {
            msg = msg.substring(1);
        }

        try {
            receivedJSON = (JSONObject) parser.parse(msg);
        } catch (ParseException e) {
            // Peng: handling parsing error
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "JSON parse error while parsing message");
            // Now sending an invalid message doesn't terminate the connection.
            connectionTerm = true;
        }


        String command = null;

        try {
            command = (String) receivedJSON.get("command");
            log.debug("Read in command: " + command);
        } catch (ClassCastException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            //e.printStackTrace();
        } catch (NumberFormatException e){

        }

        switch (command) {
            case ("LOGIN"):
                connectionTerm = login(receivedJSON, con);
                break;
            case ("LOGOUT"):
                connectionTerm = logout(con);
                break;
            case ("LOGIN_ALLOWED"):
                connectionTerm = loginAllowed(receivedJSON, con);
                break;
            case ("LOGIN_DENIED"):
                connectionTerm = loginDenied(receivedJSON, con);
                break;
            case ("LOGIN_REQUEST"):
                connectionTerm = loginRequest(receivedJSON, con);
                break;
            case ("ACTIVITY_MESSAGE"):
                logMessage(receivedJSON, con);
                connectionTerm = activityMessage(receivedJSON, con);
                break;
            case ("ACTIVITY_BROADCAST"):
                connectionTerm = activityBroadcast(receivedJSON, con);
                break;
            case ("SERVER_ANNOUNCE"):
                connectionTerm = serverAnnounce(receivedJSON, con);
                break;
            case ("AUTHENTICATE"):
                connectionTerm = authenticate(receivedJSON, con);
                break;
            case ("AUTHENTICATION_SUCCESS"):
                connectionTerm = authenticated(receivedJSON, con);
                break;
            case ("REGISTER"):
                connectionTerm = register(receivedJSON, con);
                break;
            case ("LOCK_ALLOWED"):
                connectionTerm = lockAllowed(receivedJSON, con);
                break;
            case ("LOCK_DENIED"):
                connectionTerm = lockDenied(receivedJSON, con);
                break;
            case("LOCK_REQUEST"):
                connectionTerm = lockRequest(receivedJSON, con);
                break;
            case("CLOCK_RESPONSE"):
                clockResponse(receivedJSON);
                connectionTerm = true;
            case("NEW_PARENT"):
                break;
            default:
                returnJSON.put("command", "INVALID_MESSAGE");
                returnJSON.put("info", "unknown commands");
                con.writeMsg(returnJSON.toJSONString());
                connectionTerm = true;
        }

        return connectionTerm;
    }

    /** auxillary methods for process **/
    // login deals with login under case LOGIN in process
    private boolean login(JSONObject receivedJson, Connection con) {
        // Using HashMap and JSONObject
        // Also process redirection operation here.
        String idleServer = findIdleServer();
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;
        if(idleServer != null) {
            returnJSON = redirectMessage(idleServer);
            connectionTerm = true;
            con.writeMsg(returnJSON.toJSONString());
            return connectionTerm;
        }

        String username = (String)receivedJson.get("username");
        String secret = (String)receivedJson.get("secret");

        if (userIsValid(username, secret)) {
            returnJSON.put("command", "LOGIN_SUCCESS");
            returnJSON.put("info", "logged in as user " + username);
            connectionTerm = false;
            // Change Login State
            Long currentTime = getRegularizedCurrentTime();
            loginTime.put(username, currentTime);
            loginTimeByConnection.put(con, currentTime);
            connectionToUsername.put(con, username);
            // Initializating message receiving pool
            lastSentMessageSerial.put(con, new HashMap<>());
            messageBuffer.put(con, new HashMap<>());
            // Create Client Entry in outMessageLog
            outMessageLog.put(username, new HashMap<Integer, JSONObject>());
            log.info("Created record for client: " + username);

            //TODO: need to broadcast to first server connection.
        } else {
            if(getServerCount() < 1){
                returnJSON.put("command", "LOGIN_FAILED");
                returnJSON.put("info", "attempt to login with wrong secret");
                connectionTerm = true;
            }
            //Justin: this server is connected to other servers and needs to clarify with all of them
            else{
                pendingLoginResponses.put(username, new HashMap<>());
                pendingLoginClientCons.put(username, con);
                //Justin: if no issues are encountered up until this point, the server will wait until it receives responses from other servers
                JSONObject lockJSON = new JSONObject();

                lockJSON.put("command", "LOGIN_REQUEST");
                lockJSON.put("username", username);
                lockJSON.put("secret", secret);
                broadcastToServers(lockJSON, null);

                connectionTerm = false;
            }
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean logout(Connection con){
        String username = connectionToUsername.get(con);
        // clean records.
        lastSentMessageSerial.remove(con);
        loginTimeByConnection.remove(con);
        messageBuffer.remove(con);
        connectionToUsername.remove(con);
        loginTime.remove(username);
        outMessageLog.remove(username);
        log.info("Client " + username + " logged out. Records cleaned");
        //TODO: forward to first server in connection pool.
        return true;
    }

    private boolean loginAllowed(JSONObject receivedJSON, Connection con){

        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else {
            //This server has received a login_allowed message from another server
            //meaning it now has to store that the ip of said other server has allowed the username in question
            HashMap<Connection, Boolean> respondedCons = pendingLoginResponses.get(username);
            if (respondedCons != null) {
                respondedCons.put(con, true);
                pendingLoginResponses.put(username, respondedCons);

                //Justin: now check all server responses up until this point
                JSONObject waitingClientJSON = new JSONObject();
                switch(loginAllowed(username)){
                    case ("denied"):
                        //non logging in server, send message back to sending server
                        if(pendingLoginServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingLoginServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingLoginServerCons.remove(username);
                        }
                        //logging in server
                        else if(pendingLoginClientCons.get(username) != null){
                            waitingClientJSON.put("command", "LOGIN_FAILED");
                            waitingClientJSON.put("info", username + " doesn't exist or secret is incorrect");
                            pendingLoginClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                            pendingLoginClientCons.get(username).closeCon();
                        }
                        break;
                    case ("allowed"):
                        //non logging in server, send message back to sending server
                        if(pendingLoginServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingLoginServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingLoginServerCons.remove(username);
                        }
                        //logging in server
                        else if(pendingLoginClientCons.get(username) != null){
                            accounts.put(username, secret);
                            waitingClientJSON.put("command", "LOGIN_SUCCESS");
                            waitingClientJSON.put("info", "Logged in as user " + username);
                            pendingLoginClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                        }
                        break;
                    default //continue waiting
                        break;
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean loginDenied(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else{
            //this server has received a login_denied message from another server
            //meaning it now has to store that the ip of said other server has denied the username in question
            HashMap<Connection, Boolean> respondedCons = pendingLoginResponses.get(username);
            if(respondedCons != null) {
                respondedCons.put(con, false);
                pendingLoginResponses.put(username, respondedCons);

                //now check all server responses up until this point
                JSONObject waitingClientJSON = new JSONObject();
                switch(loginAllowed(username)){
                    case ("denied"):
                        //non logging in server, send message back to sending server
                        if(pendingLoginServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingLoginServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingLoginServerCons.remove(username);
                        }
                        //logging in server
                        else if(pendingLoginClientCons.get(username) != null){
                            waitingClientJSON.put("command", "LOGIN_FAILED");
                            waitingClientJSON.put("info", username + " doesn't exist or secret is incorrect");
                            pendingLoginClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                            pendingLoginClientCons.get(username).closeCon();
                        }
                        break;
                    case ("allowed"):
                        //non logging in server, send message back to sending server
                        if(pendingLoginServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingLoginServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingLoginServerCons.remove(username);
                        }
                        //logging in server
                        else if(pendingLoginClientCons.get(username) != null){
                            accounts.put(username, secret);
                            waitingClientJSON.put("command", "LOGIN_SUCCESS");
                            waitingClientJSON.put("info", "Logged in as user " + username);
                            pendingLoginClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                        }
                        break;
                    default://continue waiting
                        break;
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean loginRequest(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else{
            //Justin: this server has received a login_request message from another server

            JSONObject lockJSON = new JSONObject();

            //Justin: server IS NOT a terminal server
            if(getServerCount() > 1 && !userIsValid(username, secret)) {
                //must now send out a login_request to all servers except the original sender
                //avoid waiting for parent server approval fail-safe, by adding the parent server to the list of approved servers
                HashMap<Connection, Boolean> respondedCons = new HashMap<>();
                respondedCons.put(con, true);
                pendingLoginResponses.put(username, respondedCons);
                pendingLoginServerCons.put(username, con);
                log.debug("broadcasting LOGIN_REQUEST");

                //if no issues are encountered up until this point, the server will wait until it receives responses from other servers

                lockJSON.put("command", "LOGIN_REQUEST");
                lockJSON.put("username", username);
                lockJSON.put("secret", secret);
                broadcastToServers(lockJSON, con);
                log.debug("4");
            }
            // server IS a terminal server and only has to check itself
            else {
                //it now has to check if the login details that the other server is checking for exists and broadcast its results
                if (!userIsValid(username, secret)) {
                    lockJSON.put("command", "LOGIN_DENIED");
                    lockJSON.put("username", username);
                    lockJSON.put("secret", secret);
                    broadcastToServers(lockJSON, null);
                } else {
                    lockJSON.put("command", "LOGIN_ALLOWED");
                    lockJSON.put("username", username);
                    lockJSON.put("secret", secret);
                    broadcastToServers(lockJSON, null);
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean activityMessage(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();

        String username = connectionToUsername.get(con);

        String id = (String)receivedJSON.get("id");
        String timestamp = (String)receivedJSON.get("timestamp");
        String serial = (String)receivedJSON.get("serial");

        JSONObject activity = (JSONObject) receivedJSON.get("activity");

        boolean connectionTerm;

        if (username == null) {
            returnJSON.put("command", "AUTHENTICATION_FAIL");
            connectionTerm = true;
        } else if (!(activity instanceof JSONObject) || (activity == null)) {
            returnJSON.put("command", "INVALID_MESSAGE");
            connectionTerm = true;
        } else {
            // Peng: process activity object
            activity.put("authenticated_user", username);
            JSONObject activityBroadcast = new JSONObject();
            broadcastToClients(receivedJSON);

            activityBroadcast.put("command", "ACTIVITY_BROADCAST");
            activityBroadcast.put("activity", activity);
            activityBroadcast.put("id", id);
            activityBroadcast.put("serial", serial);
            activityBroadcast.put("timestamp", timestamp);

            broadcastToServers(activityBroadcast, null);

            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean activityBroadcast(JSONObject receivedJSON, Connection con) {
        //TODO: Peng: complete implementations need AUTHENTICATION
        //TODO: allow activity message passing without having client input username and secret
        // Verify sender is server
        broadcastToServers(receivedJSON, con);
        boolean connectionTerm;

        JSONObject returnJSON = new JSONObject();

        if(con.getServer() == false) {
            returnJSON.put("command", "INVALID_MESSAGE");
            connectionTerm = false;
        }
        // Peng: this message is sent from another server.
        broadcastToClients(receivedJSON);
        connectionTerm = false;

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean serverAnnounce(JSONObject receivedJSON, Connection con){
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        int load = Integer.parseInt((String)receivedJSON.get("load"));
        String hostname = (String) receivedJSON.get("hostname");
        int port = Integer.parseInt((String)receivedJSON.get("port"));
        String secret = (String) receivedJSON.get("id");
        //update server load and port
        log.debug("IN SERVER_ANNOUNCE: ");
        log.debug("load: " + load);
        log.debug("port: " + port);
        if(con.getServer() == false) {
            returnJSON.put("command", "INVALID_MESSAGE");
            connectionTerm = false;
        }

        serverLoads.put(hostname, load);
        serverToPort.put(hostname, port);
        connectionTerm = false;

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean authenticate(JSONObject receivedJSON, Connection con) {
        boolean connectionTerm = false;
        JSONObject returnJSON = new JSONObject();

        int port = Integer.parseInt((String)receivedJSON.get("port"));
        String hostname = (String) receivedJSON.get("hostname");
        String sendersecret = (String) receivedJSON.get("mysecret");
        String secret = (String) receivedJSON.get("secret");
        if (isAuthenticated(secret)) {
            log.debug("Authentication success!");
            //case that the server connecting to us has been marked as our parent (should only be for case that they are the 1st user to connect to the root node)
            //Making them technically our "parent" but really they are just our backup to send new servers to/ where we replicate our consumer Q
            if (connections.get(0) == con) {
                log.debug("My parent node is: " +sendersecret);
                Settings.setParentPort(port);
                Settings.setParentSecret(sendersecret);
                Settings.setParentServer(hostname);
                //Since you have only 1 connection, there is no deadpool to send them, they won't have one.
                returnJSON.put("command", "AUTHENTICATION_SUCCESS");
                returnJSON.put("empty", "T");
                if(isroot){
                    returnJSON.put("root", "T");
                }else
                    returnJSON.put("root", "F");
            }
            else  {
                //Give the server connecting to you, the routing info for where your consumer Q backup should be
                //return a JSON object with info for their deadpool, your 1st entry (parent node)
                returnJSON.put("command", "AUTHENTICATION_SUCCESS");
                returnJSON.put("secret", Settings.getParentSecret());
                returnJSON.put("port", Integer.toString(Settings.getParentPort()));
                returnJSON.put("hostname", Settings.getParentServer());
                returnJSON.put("empty", "F");
                if(isroot){
                    returnJSON.put("root", "T");
                }else
                    returnJSON.put("root", "F");
            }
        } else{
            returnJSON.put("command", "AUTHENTICATION_FAIL");
            connectionTerm = true;
        }
            con.setServer();
            connectionTerm = false;

            if (!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

            return connectionTerm;
        }

    private boolean authenticated(JSONObject receivedJSON, Connection con) {
        boolean connectionTerm = false;
        String empty = (String)receivedJSON.get("empty");
        String root = (String)receivedJSON.get("root");
        switch (empty) {
            case ("F"):
                int port = Integer.parseInt((String) receivedJSON.get("port"));
                Settings.setBackSecret((String) receivedJSON.get("secret"));
                Settings.setbackServer((String) receivedJSON.get("hostname"));
                Settings.setbackPort(port);
                if (root == "T") {
                    con.setisRoute();
                }
                log.info("I just set up my deadpool with server: " + Settings.getbackServer() + "@port" + Settings.getbackPort() + "with secret code: " + Settings.getBackSecret());
                break;
            default:
                int deadport = 1;
                Settings.setBackSecret(null);
                Settings.setbackPort(deadport);
                Settings.setBackSecret(null);
                if (root == "T") {
                    con.setisRoute();
                }
                log.info("You're the first member of the root nodes list, by default your backup port should be 1 and it is:" +Settings.getbackPort());
        }
        return connectionTerm;
    }


    private boolean newparent(JSONObject receivedJSON, Connection con) {
        boolean connectionTerm = false;
        JSONObject newparentJSON = new JSONObject();// create new JSON object for AUTHENTICATION MSG
        newparentJSON.put("command","AUTHENTICATE");
        newparentJSON.put("port", Integer.toString(Settings.getLocalPort()));
        newparentJSON.put("hostname", Settings.getLocalHostname());
        newparentJSON.put("mysecret", Settings.getMySecret());

        //reset your backsecret to no one
        int deadport = 1;
        Settings.setBackSecret(null);
        Settings.setbackPort(deadport);
        Settings.setBackSecret(null);
        log.info("I'm the new backup for the root node by dead-pool is blank, port should be 1 and is:" +Settings.getbackPort());

        if (!newparentJSON.isEmpty()) {
            con.writeMsg(newparentJSON.toJSONString());
        }
        return connectionTerm;
    }

    private boolean register(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String)receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else {
            // checks if the username exists within this server, or any others
            if(usernameExists(username)) {
                returnJSON.put("command", "REGISTER_FAILED");
                returnJSON.put("info", username + " is already registered with the system");
                connectionTerm = true;
            }
            else {
                if(getServerCount() < 1){
                    accounts.put(username, secret);
                    returnJSON.put("command", "REGISTER_SUCCESS");
                    returnJSON.put("info", "register success for " + username);

                    connectionTerm = false;
                }
                //this server is connected to other servers and needs to clarify with all of them
                else{
                    pendingAccountUsernameResponses.put(username, new HashMap<>());
                    pendingAccountUsernameClientCons.put(username, con);
                    //Justin: if no issues are encountered up until this point, the server will wait until it receives responses from other servers
                    JSONObject lockJSON = new JSONObject();

                    lockJSON.put("command", "LOCK_REQUEST");
                    lockJSON.put("username", username);
                    lockJSON.put("secret", secret);
                    broadcastToServers(lockJSON, null);

                    connectionTerm = false;
                }
            }
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean lockAllowed(JSONObject receivedJSON, Connection con){

        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else {
            //this server has received a lock_allowed message from another server
            //meaning it now has to store that the ip of said other server has allowed the username in question
            HashMap<Connection, Boolean> respondedCons = pendingAccountUsernameResponses.get(username);
            if (respondedCons != null) {
                respondedCons.put(con, true);
                pendingAccountUsernameResponses.put(username, respondedCons);

                // now check all server responses up until this point
                JSONObject waitingClientJSON = new JSONObject();
                switch(lockAllowed(username)){
                    case ("denied"):
                        //non registering server, send message back to sending server
                        if(pendingAccountUsernameServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingAccountUsernameServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingAccountUsernameServerCons.remove(username);
                        }
                        //registering server
                        else if(pendingAccountUsernameClientCons.get(username) != null){
                            waitingClientJSON.put("command", "REGISTER_FAILED");
                            waitingClientJSON.put("info", username + " is already registered with the system");
                            pendingAccountUsernameClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                            pendingAccountUsernameClientCons.get(username).closeCon();
                        }
                        break;
                    case ("allowed"):
                        //non registering server, send message back to sending server
                        if(pendingAccountUsernameServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingAccountUsernameServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingAccountUsernameServerCons.remove(username);
                        }
                        //registering server
                        else if(pendingAccountUsernameClientCons.get(username) != null){
                            accounts.put(username, secret);
                            waitingClientJSON.put("command", "REGISTER_SUCCESS");
                            waitingClientJSON.put("info", "register success for " + username);
                            pendingAccountUsernameClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                        }
                        break;
                    default://continue waiting
                        break;
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean lockDenied(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else{
            //this server has received a lock_denied message from another server
            //meaning it now has to store that the ip of said other server has denied the username in question
            HashMap<Connection, Boolean> respondedCons = pendingAccountUsernameResponses.get(username);
            if(respondedCons != null) {
                respondedCons.put(con, false);
                pendingAccountUsernameResponses.put(username, respondedCons);

                //Justin: now check all server responses up until this point
                JSONObject waitingClientJSON = new JSONObject();
                switch(lockAllowed(username)){
                    case ("denied"):
                        //non registering server, send message back to sending server
                        if(pendingAccountUsernameServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingAccountUsernameServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingAccountUsernameServerCons.remove(username);
                        }
                        //registering server
                        else if(pendingAccountUsernameClientCons.get(username) != null){
                            waitingClientJSON.put("command", "REGISTER_FAILED");
                            waitingClientJSON.put("info", username + " is already registered with the system");
                            pendingAccountUsernameClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                            pendingAccountUsernameClientCons.get(username).closeCon();
                        }
                        break;
                    case ("allowed"):
                        //non registering server, send message back to sending server
                        if(pendingAccountUsernameServerCons.get(username) != null){
                            returnJSON = receivedJSON;
                            pendingAccountUsernameServerCons.get(username).writeMsg(returnJSON.toJSONString());
                            pendingAccountUsernameServerCons.remove(username);
                        }
                        //registering server
                        else if(pendingAccountUsernameClientCons.get(username) != null){
                            accounts.put(username, secret);
                            waitingClientJSON.put("command", "REGISTER_SUCCESS");
                            waitingClientJSON.put("info", "register success for " + username);
                            pendingAccountUsernameClientCons.get(username).writeMsg(waitingClientJSON.toJSONString());
                        }
                        break;
                    default://Justin: continue waiting
                        break;
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private boolean lockRequest(JSONObject receivedJSON, Connection con) {
        JSONObject returnJSON = new JSONObject();
        boolean connectionTerm = true;

        String username = (String) receivedJSON.get("username");
        String secret = (String) receivedJSON.get("secret");

        if(username == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "username was not specified");
            connectionTerm = true;
        }
        else if (secret == null) {
            returnJSON.put("command", "INVALID_MESSAGE");
            returnJSON.put("info", "secret was not specified");
            connectionTerm = true;
        }
        else{
            //this server has received a lock_request message from another server

            JSONObject lockJSON = new JSONObject();

            //Justin: server IS NOT a terminal server
            if(getServerCount() > 1 && usernameExists(username) == false) {
                // must now send out a lock_request to all servers except the original sender
                // avoid waiting for parent server approval fail-safe, by adding the parent server to the list of approved servers
                HashMap<Connection, Boolean> respondedCons = new HashMap<>();
                respondedCons.put(con, true);
                pendingAccountUsernameResponses.put(username, respondedCons);
                pendingAccountUsernameServerCons.put(username, con);
                log.debug("broadcasting LOCK_REQUEST");

                //if no issues are encountered up until this point, the server will wait until it receives responses from other servers

                lockJSON.put("command", "LOCK_REQUEST");
                lockJSON.put("username", username);
                lockJSON.put("secret", secret);
                broadcastToServers(lockJSON, con);
                log.debug("4");
            }
            //server IS a terminal server and only has to check itself
            else {
                //it now has to check if the username that the other server is checking for exists and broadcast its results
                if (usernameExists(username)) {
                    lockJSON.put("command", "LOCK_DENIED");
                    lockJSON.put("username", username);
                    lockJSON.put("secret", secret);
                    broadcastToServers(lockJSON, null);
                } else {
                    lockJSON.put("command", "LOCK_ALLOWED");
                    lockJSON.put("username", username);
                    lockJSON.put("secret", secret);
                    broadcastToServers(lockJSON, null);
                }
            }
            connectionTerm = false;
        }

        if(!returnJSON.isEmpty())
            con.writeMsg(returnJSON.toJSONString());

        return connectionTerm;
    }

    private void logMessage(JSONObject receivedJSON, Connection con) {
        String username = connectionToUsername.get(con);
        Integer serial = Integer.parseInt((String)receivedJSON.get("serial"));

        if(username != null && !username.equals("anonymous"))
            outMessageLog.get(username).put(serial, receivedJSON);
        log.info("Message #" + serial + " of client " + username + " archived");
    }

    private void clockResponse(JSONObject receivedJSON){
        String time = (String) receivedJSON.get("time");
        remoteStartTime = Long.parseLong(time);
        log.debug("Received from clock server: " + remoteStartTime.toString());
        log.debug("Offset: " + offset);

    }
    /* check if a user is valid */
    private  boolean userIsValid(String username, String secret) {
        if(username == null) {
            log.error("username field is null");
            return false;
        }
        else if(secret == null) {
            if (username.equals("anonymous"))
                return true;
            else {
                log.error("secret field is null");
                return false;
            }
        }
        else if(accounts.get(username) == null){
            return false;
        }
        else if(accounts.get(username).equals(secret))
            return true;
        else
            return false;
    }

    private boolean needsToSend(Connection connection, JSONObject receivedJSON){
        Long timestamp = Long.parseLong((String)receivedJSON.get("timestamp"));
        if(timestamp == null)
            return false;

        Long loginTimeOfCon = loginTime.get(connectionToUsername.get(connection));

        if(timestamp > loginTimeOfCon)
            return true;
        else
            return false;
    }



    /* check if a user has logged in */
    public boolean userIsLoggedIn(String username){
        if(username == null) {
            log.error("username is null");
            return false;
        }
        if(loginTime.containsKey(username))
            return true;
        else {
            log.error("User "+ username + "not in loggin list");
            return false;
        }
    }

    public void askForCurrentTime() {

        JSONObject outJSON = new JSONObject();
        outJSON.put("command", "GET_TIME");
        try {
            Socket socketToClockServer = new Socket(Settings.getClockHostName(), Settings.getClockPort());
            Connection outCon = new Connection(socketToClockServer);
            outCon.writeMsg(outJSON.toJSONString());
            log.info("Time query sent to Clock Server.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* broadcast to all clients connected to this server */
    public void broadcastToClients(JSONObject json){
        log.debug("message to broadcast", json.toJSONString());
        int id = Integer.parseInt((String)json.get("id"));
        int serial = Integer.parseInt((String)json.get("serial"));

        for(Connection connection: loginTimeByConnection.keySet()){
            if(!connection.getServer()) {
                if(!lastSentMessageSerial.containsKey(connection))
                    lastSentMessageSerial.put(connection, new HashMap<>());
                if(!messageBuffer.containsKey(connection))
                    messageBuffer.put(connection, new HashMap<>());

                if(!messageBuffer.get(connection).containsKey(id))
                    messageBuffer.get(connection).put(id, new ArrayList<>());

                // private HashMap<Connection, HashMap<Integer, Integer>> lastSentMessageSerial = new HashMap<>();
                // private HashMap<Connection, HashMap<Integer, ArrayList<JSONObject>>> messageBuffer = new HashMap<>();
                // First message comes
                /**
                 * if lastSentID is not null, compare it with new messages serial:
                 *      if serial = lastSentID + 1, send it, update lastSentID, and exhaust buffered list, update lastSentID
                 *      if serial != lastSentID + 1, buffer the message
                 * if lastSentID is null,
                 *      if serial == id, this is first incoming message, send it, and exhaust buffered list, update lastSentID
                 *      else buffer the message
                 */
                int lastSentSerial;
                ArrayList<JSONObject> thisBuffer = messageBuffer.get(connection).get(id);
                if(lastSentMessageSerial.get(connection).containsKey(id)){
                    lastSentSerial = lastSentMessageSerial.get(connection).get(id);
                    if(serial == lastSentSerial + 1){
                        connection.writeMsg(json.toJSONString());
                        lastSentSerial++;
                        lastSentSerial = sendMessageInQueue(thisBuffer, lastSentSerial, connection);
                        lastSentMessageSerial.get(connection).put(id, lastSentSerial);
                        messageBuffer.get(connection).put(id, removeSentMessage(thisBuffer, lastSentSerial));
                    }
                } else if(serial == id) {
                    lastSentSerial = serial;
                    connection.writeMsg(json.toJSONString());
                    lastSentMessageSerial.get(connection).put(id, lastSentSerial);
                    thisBuffer = messageBuffer.get(connection).get(id);
                    lastSentSerial = sendMessageInQueue(thisBuffer, lastSentSerial, connection);
                    lastSentMessageSerial.get(connection).put(id, lastSentSerial);
                    messageBuffer.get(connection).put(id, removeSentMessage(thisBuffer, lastSentSerial));
                } else if(messageBuffer.get(connection).containsKey(id)) {
                    insertToList(messageBuffer.get(connection).get(id), json);
                }
            }
        }
    }

    private ArrayList<JSONObject> insertToList(ArrayList<JSONObject> list, JSONObject json) {
        Integer serial = Integer.parseInt((String)json.get("serial"));
        int index = -1;
        for(int i = 0; i < list.size(); i++) {
            JSONObject iJson = list.get(i);
            Integer iSerial = Integer.parseInt((String) iJson.get("serial"));
            if (serial<iSerial) {
                index = i;
                break;
            }
        }
        if(index == -1)
            list.add(json);
        else
            list.add(index, json);

        return list;
    }

    private int sendMessageInQueue(ArrayList<JSONObject> list, int lastSentSerial, Connection connection) {
        int length = list.size();
        int iSerial;
        JSONObject iJson;
        for (int i = 0; i < length; i++){
            iJson = list.get(i);
            iSerial = Integer.parseInt((String) iJson.get("serial"));
            if(iSerial == lastSentSerial + 1){
                connection.writeMsg(iJson.toJSONString());
                lastSentSerial++;
            } else
                break;
        }

        return lastSentSerial;
    }

    private ArrayList<JSONObject> removeSentMessage(ArrayList<JSONObject> list, int lastSentSerial) {
        int length = list.size();
        int iSerial;
        JSONObject iJson;
        for (int i = 0; i < length; i++) {
            iJson = list.get(i);
            iSerial = Integer.parseInt((String) iJson.get("serial"));
            if (iSerial <= lastSentSerial)
                list.remove(i);
        }
        return list;
    }

    /* broadcast to all servers */
    public void broadcastToServers(JSONObject json, Connection con){
        if(con == null) {
            for(Connection conn: connections) {
                if (conn.getServer()) {
                    conn.writeMsg(json.toJSONString());
                }
            }
        } else {
            for (Connection conn: connections) {
                if (conn.getServer())
                    if (con != conn)
                        conn.writeMsg(json.toJSONString());
            }
        }
    }
    /* Check input secret message against our stored message */
    public boolean isAuthenticated(String secret){
        if(secret == null) {
            log.error("secret is null");
            return false;
        }
        if(Objects.equals(serverID, secret)){
            return true;
        }
        else {
            log.error("secret "+ secret + "not in loggin list");
            return false;
        }
    }

    public boolean usernameExists(String username){
        if(accounts.get(username) == null){
            return false;
        }
        else{
            return true;
        }
    }

    public String lockAllowed(String username){

        int allowedResponses = 0;

        // Get the haspmap being stored a the key of the pending username
        //Go through all of its contents and return denied if any server has denied this request
        //return allowed if all of the servers have allowed this request
        //return pending if all the servers have yet to respond
        HashMap<Connection, Boolean> respondedCons = pendingAccountUsernameResponses.get(username);
        for (Connection conKey: respondedCons.keySet()){
            if(conKey.getServer()) {
                Boolean allowed = respondedCons.get(conKey);
                if (allowed != null) {
                    if (allowed == false) {
                        return "denied";
                    } else {
                        allowedResponses++;
                    }
                }
            }

        }
        if(allowedResponses < getServerCount()){
            log.debug("Server is waiting for lock responses");
            log.debug("Server count = " + Integer.toString(getServerCount()));
            log.debug("allowed responses = " + Integer.toString(allowedResponses));
            return "pending";
        }
        else{
            return "allowed";
        }
    }

    public String loginAllowed(String username){

        int allowedResponses = 0;

        //Get the haspmap being stored a the key of the pending username
        //Go through all of its contents and return denied if any server has denied this request
        //return allowed if all of the servers have allowed this request
        //return pending if all the servers have yet to respond
        HashMap<Connection, Boolean> respondedCons = pendingLoginResponses.get(username);
        for (Connection conKey: respondedCons.keySet()){
            if(conKey.getServer()) {
                Boolean allowed = respondedCons.get(conKey);
                if (allowed != null) {
                    if (allowed == false) {
                        return "denied";
                    } else {
                        allowedResponses++;
                    }
                }
            }

        }
        if(allowedResponses < getServerCount()){
            log.debug("Server is waiting for login responses");
            log.debug("Server count = " + Integer.toString(getServerCount()));
            log.debug("allowed responses = " + Integer.toString(allowedResponses));
            return "pending";
        }
        else{
            return "allowed";
        }
    }

    public int getServerCount(){
        int count = 0;
        for(int i = 0; i < connections.size(); i++){
            if(connections.get(i).getServer()){
                count++;
            }
        }
        return count;
    }

    // Aaron's methods

    /*
     * The connection has been closed by the other party.
     */
    public synchronized void connectionClosed(Connection con) {
        boolean reroute = false;
        boolean newparent = false;
        boolean lonewolf = false;
        //getRemoteSocketAddress() : Returns:address of the endpoint this socket is connected to, or null if it is unconnected.
        String deadhostname = con.getSocket().getRemoteSocketAddress().toString();
        log.debug("--------Server at remote port: " + deadhostname +  "has failed, RIP---------------");
       //rework your childs dead-pool, if a child was marked as the roots parent (for replication)
        //Only the root node should do this

        if(!connections.isEmpty())
            if(con == connections.get(0)){
                if(isroot) {
                    newparent = true;
                    log.debug("Root servers backup has died, reset to:"+connections.get(1).getSocket());

                }
                //reset route if your parent died, they are the route node, you are their backup
                if(connections.get(0).getisRoute() && (Settings.getbackPort() == 1)){
                    isroot = true;
                }
            }

        //if server that died is my parent (1st member of my cons list) & I haven't become the 1st con in its list
        // connect to 1st member of my backup list.
        if (con.getisParent()) {
            if (Settings.getBackSecret() != null && Settings.getbackPort() != 1) {
                reroute = true;
            }
        }
        //close the original connection
        if (!term) connections.remove(con);

        //mark if parent has no children, we don't want to try and send NEW_PARENT to no one.
        /*
        if(connections.isEmpty()){
            lonewolf = true;
        }
        */
        if (reroute) {
            runtimeServer(Settings.getBackSecret(), Settings.getbackServer(), Settings.getbackPort());
            log.debug("Initiating dead-pool reconnect to:" + Settings.getbackServer() + "@ port" + Settings.getbackPort());
        }
        /*if(newparent) {
            JSONObject returnJSON = new JSONObject();
            returnJSON.put("command", "NEW_PARENT");
            returnJSON.put("secret", Settings.getParentSecret());
            returnJSON.put("port", Integer.toString(Settings.getParentPort()));
            returnJSON.put("hostname", Settings.getParentServer());
            if (!returnJSON.isEmpty()) {
                connections.get(0).writeMsg(returnJSON.toJSONString());
            }
        }*/
    }
    /*
     * A new incoming connection has been established, and a reference is returned to it
     */
    public synchronized Connection incomingConnection(Socket s) throws IOException{
        log.debug("incomming connection: "+Settings.socketAddress(s));
        Connection c = new Connection(s);
        //if this is the first connection in this list -> its this servers parent (for failure model -> where it will send its )
        boolean parent = false;
        if (connections.isEmpty()) {
            parent = true;
        }

        connections.add(c);
        if (parent) {
            c.setParent();
        }
        return c;
    }

    /*
     * A new outgoing connection has been established, and a reference is returned to it
     */
    public synchronized Connection outgoingConnection(Socket s) throws IOException{
        log.debug("outgoing connection to: "+Settings.socketAddress(s));
        Connection c = new Connection(s);

        connections.add(c);
        return c;

    }

    @Override
    public void run(){
        startTime = System.currentTimeMillis();
        log.info("using activity interval of "+Settings.getActivityInterval()+" milliseconds");
        askForCurrentTime();


        //Peng: Only initiate connections when remote server exists
        if(Settings.getRemoteHostname() != null) {
            log.info("Initializing connection to remote server");
            initiateConnection();
        } else {
            log.info("No initial remote server provided");
            isroot = true;
        }


        while(!term){
            // do something with 5 second intervals in between
            try {
                Thread.sleep(Settings.getActivityInterval());
            } catch (InterruptedException e) {
                log.info("received an interrupt, system is shutting down");
                break;
            }
            if(!term){
                log.debug("Announce to other servers: ");
                term=doActivity();
            }

        }
        log.info("closing "+connections.size()+" connections");
        // clean up
        for(Connection connection : connections){
            connection.closeCon();
        }
        listener.setTerm(true);
    }

    public boolean doActivity(){
        // Announce to other server
        log.debug("List of Connected Servers");
        for(Connection con : connections) {
            String hostname = con.getSocket().getRemoteSocketAddress().toString();
            int portnum = con.getSocket().getPort();
            log.debug("Server " + hostname + " " + portnum + "connected");
        }
        JSONObject announce = new JSONObject();
        announce.put("command", "SERVER_ANNOUNCE");
        announce.put("id", serverID);
        announce.put("load", Integer.toString(connections.size()));
        announce.put("hostname", Settings.getLocalHostname());
        announce.put("port", Integer.toString(Settings.getLocalPort()));
        log.debug("Announcing: " + announce.toJSONString());
        broadcastToServers(announce, null);
        return false;
    }

    // method used for Redirection.
    public String findIdleServer(){
        if(serverLoads.isEmpty())
            return null;

        String hostname = null;
        int minLoad = Integer.MAX_VALUE;
        int localLoad = connections.size();

        for(String key: serverLoads.keySet()){
            if(serverLoads.get(key) < minLoad){
                minLoad = serverLoads.get(key);
                hostname = key;
            }
        }

        if (minLoad >= localLoad - 1)
            return null;

        return hostname;
    }

    public JSONObject redirectMessage(String hostname){
        int port;
        try{
            port = serverToPort.get(hostname);
        } catch (NullPointerException e){
            return null;
        }

        JSONObject redirectJSON = new JSONObject();
        redirectJSON.put("command", "REDIRECT");
        redirectJSON.put("hostname", hostname);
        redirectJSON.put("port", Integer.toString(port));

        return redirectJSON;
    }

    public Long getRegularizedCurrentTime(){
        if(remoteStartTime == null) {
            log.error("Regularized time unavailable before local server clock synchronization.");
            return null;
        }

        Long currentTime = System.currentTimeMillis();
        return currentTime - startTime + remoteStartTime;
    }

    public Long getRemoteStartTime(){
        return remoteStartTime;
    }


    public final void setTerm(boolean t){
        term=t;
    }

    public final ArrayList<Connection> getConnections() {
        return connections;
    }
}
