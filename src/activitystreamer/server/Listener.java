package activitystreamer.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import activitystreamer.util.Settings;

public class Listener extends Thread{
	private static final Logger log = LogManager.getLogger();
	private ServerSocket serverSocket=null;
	private boolean term = false;
	private int portnum;
	
	public Listener() throws IOException{
		portnum = Settings.getLocalPort(); // keep our own copy in case it changes later
		serverSocket = new ServerSocket(portnum);
		start();
	}
	
	@Override
	public void run() {
		// Thread blocks until clock synchronized.
		while(Control.getInstance().getRemoteStartTime() == null) {
			log.info("Waiting for clock server response");
			try {
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		log.info("Server Clock synchronized");
		log.info("listening for new connections on "+portnum);
		while(!term){
			Socket clientSocket;
			try {
				clientSocket = serverSocket.accept();
				Control.getInstance().incomingConnection(clientSocket);
			} catch (IOException e) {
				log.info("received exception, shutting down");
				term=true;
			}
		}
	}

	public void setTerm(boolean term) {
		this.term = term;
		if(term) interrupt();
	}

}
