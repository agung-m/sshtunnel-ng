package org.programmerplanet.sshtunnel.model;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jcraft.jsch.ServerSocketFactory;

/**
 * Creates the listening sockets of the local tunnels of one SSH connection and keeps track of
 * them and of the connections they accept, so that they can all be closed.
 */
public class TrackedServerSocketFactory implements ServerSocketFactory {
	private Map<String, ServerSocket> socketMap;
	
	public TrackedServerSocketFactory() {
		this.socketMap = new ConcurrentHashMap<>();
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
		//ServerSocket socket = new ServerSocket(port, backlog, bindAddr);	
		ServerSocket serverSocket = new CustomServerSocket(port, backlog, bindAddr);
		String key = bindAddr.toString() + ":" + Integer.toString(port);
		socketMap.put(key, serverSocket);
		return serverSocket;
	}

	public void closeSocket(String addr, int port) {
		try {
			InetAddress bindAddr = InetAddress.getByName(normalize(addr));
			
			String key = bindAddr.toString() + ":" + Integer.toString(port);
			ServerSocket socket = socketMap.remove(key);
			if (socket != null) {
				close((CustomServerSocket) socket);
			}
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Closes all listening sockets of this factory and the connections they accepted.
	 */
	public void closeAll() {
		for (Iterator<ServerSocket> i = socketMap.values().iterator(); i.hasNext();) {
			ServerSocket socket = i.next();
			i.remove();
			close((CustomServerSocket) socket);
		}
	}

	private void close(CustomServerSocket socket) {
		socket.closeTunnelSockets();
		try {
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static String normalize(String address){
	    if(address!=null){
	      if(address.length()==0 || address.equals("*"))
	        address="0.0.0.0";
	      else if(address.equals("localhost"))
	        address="127.0.0.1";
	    }
	    return address;
	}

}

class CustomServerSocket extends ServerSocket {

	// Connections accepted by this tunnel, added by the JSch port watcher thread
	private final List<Socket> tunnelSockets = new CopyOnWriteArrayList<>();
	
	public CustomServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
		super(port, backlog, bindAddr);
	}

	@Override
	public Socket accept() throws IOException {
		Socket socket = super.accept();
		// Forget the connections that have ended, so the list does not grow forever
		tunnelSockets.removeIf(Socket::isClosed);
		tunnelSockets.add(socket);
		return socket;
	}
	
	public void closeTunnelSockets() {
		for (Socket socket: tunnelSockets) {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			tunnelSockets.remove(socket);
		}
	}

	
}
