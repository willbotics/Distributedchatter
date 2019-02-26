# 2018.5.23
## ClockServer.java
* Port 3779 is reserved for this server
* ClockServer accepts command in format:
> {"command": "GET_TIME"}
* ClockServer respond with:
> {"command": "CLOCK_RESPONSE", "time": "currentTime"}
* currentTime is got by ```System.currentTimeMillis()```
* Server request Clock Server for time on start
* Listener starts to accept incoming connection only after server clock is synchronized.
* 

* All other servers will create an outgoing connection to ClockServer, 
whenever it needs time, and close that connection once it gets result.

# 2018.5.25
## server/Control.java
* changed ```HashMap<String, Boolean> loginState``` to ```HashMap<String, Long> loginTime```, to record
login time of each user. Entry will be removed on user logout
* added ```HashMap<Connection, String> connectionToUsername```, 
to record association of client connection to username. 
Entry will be removed on user logout
##### TODO: need first server in connection pool
* forward loginTime on new client login
* forward on client logout
## client/ClientSkeleton.java
* added attribute ```int activityCounter```, to keep track of number of sent activity messages.
* client will put a new field "serial" to activity object.

# 2018.5.26
## client/ClientSkeleton.java, client/TextFrame.java
* add activity buffer, ```HashMap<Integer, JSONObject> activityBuffer```for sent activity messages. 
Key is serial number.
* add local clock to client, and clock synchronization.
* add ```public JSONObject preprocess()``` to timestamp activity messages.

## server/Control.java
* add ```HashMap<String, HashMap<Integer, JSONObject>> messageBuffer```, to serve as message log for later query.
```HashMap<username, HashMap<serial, activityObject```
* server will create an entry for each logged in client (not anonymous)
* server will clean this record when client logged out

## client/ClientSkeleton.java 
* Changed message activity sending behaviour. Now user don't have to input username and password.
* New message format:```{"command": "ACTIVITY_MESSAGE", "activity": {"message": "hello"}}```

#Login from anywhere. Added LOGIN_REQUEST, LOGIN_DENIED and LOGIN_ALLOWED. -Justin

# 2018.5.27
## server/Control.java
* implemented message queue, to guarantee in order message delivery.
