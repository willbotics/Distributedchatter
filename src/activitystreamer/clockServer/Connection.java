package activitystreamer.clockServer;


import activitystreamer.util.Settings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.Socket;


public class Connection extends Thread {
	private static final Logger log = LogManager.getLogger();
	private DataInputStream in;
	private DataOutputStream out;
	private BufferedReader inreader;
	private PrintWriter outwriter;
	private boolean open = false;
	private Socket socket;
	private boolean term=false;
	private boolean isServer = false;

	Connection(Socket socket) throws IOException{
		in = new DataInputStream(socket.getInputStream());
	    out = new DataOutputStream(socket.getOutputStream());
	    inreader = new BufferedReader( new InputStreamReader(in));
	    outwriter = new PrintWriter(out, true);
	    this.socket = socket;
	    open = true;
	    start();
	}
	
	/*
	 * returns true if the message was written, otherwise false
	 */
	public boolean writeMsg(String msg) {
		if(open){
			outwriter.println(msg);
			outwriter.flush();
			log.debug("Message sent: " + msg);
			return true;	
		}
		return false;
	}
	
	public void closeCon(){
		if(open){
			log.info("closing connection "+Settings.socketAddress(socket));
			try {
				term=true;
				inreader.close();
				out.close();
			} catch (IOException e) {
				// already closed?
				log.error("received exception closing the connection "+Settings.socketAddress(socket)+": "+e);
			}
		}
	}
	
	
	public void run(){
	    //Peng: Added debug to check connection establishment.
		log.debug("Connection in operation");
		try {
			String data;
			//Peng: Added debug to check the value of term
			log.debug("term in Connection: " + term);
			//Peng: inreader.readLine() blocks until it receives a "\n" character.
			while(!term && (data = inreader.readLine())!=null){
				//Peng: Added debug to check data receiving
				System.out.println("Received message from client");
				log.debug("Received message from client: " + data);
				term=Control.getInstance().process(this,data);
			}
			log.debug("connection closed to "+Settings.socketAddress(socket));
			Control.getInstance().connectionClosed(this);
			in.close();
		} catch (IOException e) {
			log.error("connection "+Settings.socketAddress(socket)+" closed with exception: "+e);
			Control.getInstance().connectionClosed(this);
		}
		open=false;
	}
	
	public Socket getSocket() {
		return socket;
	}
	
	public boolean isOpen() {
		return open;
	}

	public void setServer(){ this.isServer = true; }

	public boolean getServer(){ return this.isServer; }

	public boolean equals(Connection that) {
		return (this.getSocket().getRemoteSocketAddress() == that.getSocket().getRemoteSocketAddress()) &&
				(this.getSocket().getPort() == that.getSocket().getPort());
	}

}
