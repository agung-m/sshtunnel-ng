/*
 * Copyright 2009 Joseph Fifield
 * Copyright 2022 Mulya Agung
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.programmerplanet.sshtunnel.model;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.swt.widgets.Shell;
import org.programmerplanet.sshtunnel.ui.DefaultUserInfo;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

/**
 * Responsible for connecting and disconnecting ssh connections and the
 * underlying tunnels.
 * 
 * @author <a href="jfifield@programmerplanet.org">Joseph Fifield</a>
 * @author <a href="agungm@outlook.com">Mulya Agung</a>
 */
public class ConnectionManager {

	private static final Log log = LogFactory.getLog(ConnectionManager.class);

	private static final int TIMEOUT = 20000;
	// A keepalive is sent after 15s without traffic, and JSch drops the session after 3 unanswered
	// ones. A dead connection is detected after about a minute instead of two.
	private static final int KEEP_ALIVE_INTERVAL = 15000;
	private static final int KEEP_ALIVE_COUNT_MAX = 3;
	// On a live connection something arrives at least every KEEP_ALIVE_INTERVAL (the keepalive
	// reply). If nothing arrived for this long, the connection is dead even if JSch did not notice.
	private static final long STALL_TIMEOUT = 90000;
	private static final String DEF_CIPHERS = "aes128-gcm@openssh.com,chacha20-poly1305@openssh.com,aes128-cbc,aes128-ctr";

	private static final ConnectionManager INSTANCE = new ConnectionManager();
	
	public static ConnectionManager getInstance() {
		return INSTANCE;
	}
	
	enum TunnelUpdateState {
		START,
		STOP,
		CHANGE
	}
	
//	public ConnectionManager() {
//		//JSch.setLogger(new MyLogger(log));
//		JSch.setLogger(new SshLogger("/tmp"));
//	}

	// Accessed from the UI thread, the connect threads and the connection monitor
	private final Map<Session, Connection> connections = new ConcurrentHashMap<Session, Connection>();
	private final Set<Session> connecting = ConcurrentHashMap.newKeySet();

	public void connect(Session session, Shell parent) throws ConnectionException {
		if (!connecting.add(session)) {
			log.info("Session is already connecting: " + session);
			return;
		}
		try {
			openConnection(session, parent);
		} finally {
			connecting.remove(session);
		}
	}

	private void openConnection(Session session, Shell parent) throws ConnectionException {
		log.info("Connecting session: " + session);
		Connection existing = connections.get(session);
		if (existing != null) {
			if (existing.isConnected()) {
				return;
			}
			// The connection died but was not cleaned up yet. Its JSch session must not be reused:
			// JSch keeps the cipher state of the old connection, so the next handshake fails with
			// "Packet corrupt". Every connect starts with a new JSch session.
			closeConnection(session, existing);
		}
		clearTunnelExceptions(session);
		Connection connection = null;
		try {
			JSch jsch = new JSch();
			File knownHosts = getKnownHostsFile();
			jsch.setKnownHosts(knownHosts.getAbsolutePath());

			if (session.getIdentityPath() != null && session.getIdentityPath().trim().length() > 0) {
				try {
					if (session.getPassPhrase() != null && session.getPassPhrase().trim().length() > 0) {
						jsch.addIdentity(session.getIdentityPath(), session.getPassPhrase());
					} else {
						jsch.addIdentity(session.getIdentityPath());
					}
				} catch (JSchException e) {
					e.printStackTrace();
					// Jsch does not support newer format, you may convert the key to the pem format:
					// ssh-keygen -p -f key_file -m pem -P passphrase -N passphrase
					//log.error("Invalid private key: " + session.getIdentityPath(), e);
					throw new ConnectionException(e);
				}
			}
			com.jcraft.jsch.Session jschSession = jsch.getSession(session.getUsername(), session.getHostname(), session.getPort());

			// Set debug logger if set
			if (session.getDebugLogPath() != null && session.getDebugLogPath().trim().length() > 0) {
				jschSession.setLogger(new SshLogger(session.getDebugLogPath()
						+ File.separator + "sshtunnelng-" + session.getSessionName() + ".log"));
			}
			connection = new Connection(jschSession);

			UserInfo userInfo = null;
			if (session.getPassword() != null && session.getPassword().trim().length() > 0) {
				userInfo = new DefaultUserInfo(parent, session.getPassword());
			} else {
				userInfo = new DefaultUserInfo(parent);
			}
			
			jschSession.setUserInfo(userInfo);
			jschSession.setServerAliveInterval(KEEP_ALIVE_INTERVAL);
			jschSession.setServerAliveCountMax(KEEP_ALIVE_COUNT_MAX);
			
			if (session.getCiphers() != null && !session.getCiphers().isEmpty()) {
				// Set ciphers to use aes128-gcm if possible, as it is fast on many systems
				jschSession.setConfig("cipher.s2c", session.getCiphers() + "," + DEF_CIPHERS);
				jschSession.setConfig("cipher.c2s", session.getCiphers() + "," + DEF_CIPHERS);
				jschSession.setConfig("CheckCiphers", session.getCiphers());
			}
		    
		    if (session.isCompressed()) {
		    	jschSession.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
		        jschSession.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
		        //jschSession.setConfig("compression_level", "9");
		    }
		    
			jschSession.connect(TIMEOUT);

			startTunnels(session, connection);
		} catch (JSchException e) {
			if (connection != null) {
				connection.close();
			}
			throw new ConnectionException(e);
		}
		connections.put(session, connection);
	}

	private File getKnownHostsFile() {
		String userHome = System.getProperty("user.home");
		File f = new File(userHome);
		f = new File(f, ".ssh");
		f = new File(f, "known_hosts");
		return f;
	}

	private void startTunnels(Session session, Connection connection) {
		for (Iterator<Tunnel> i = session.getTunnels().iterator(); i.hasNext();) {
			Tunnel tunnel = i.next();
			try {
				startTunnel(connection, tunnel);
			} catch (Exception e) {
				tunnel.setException(e);
				log.error("Error starting tunnel: " + tunnel, e);
			}
		}
	}

	private void startTunnel(Connection connection, Tunnel tunnel) throws JSchException {
		com.jcraft.jsch.Session jschSession = connection.jschSession;
		if (tunnel.getLocal()) {
			//jschSession.setPortForwardingL(tunnel.getLocalAddress(), tunnel.getLocalPort(), tunnel.getRemoteAddress(), tunnel.getRemotePort());
			jschSession.setPortForwardingL(tunnel.getLocalAddress(),
					tunnel.getLocalPort(), tunnel.getRemoteAddress(),
					tunnel.getRemotePort(), connection.serverSocketFactory);
		} else {
			jschSession.setPortForwardingR(tunnel.getRemoteAddress(), tunnel.getRemotePort(), tunnel.getLocalAddress(), tunnel.getLocalPort());
		}
	}
	
	private int updateTunnelIfSessionConnected(Session session, TunnelUpdateState state, Tunnel tunnel, Tunnel prevTunnel) {
		int status = 0;
		Connection connection = connections.get(session);
		if (connection != null && connection.isConnected()) {
			try {
				switch (state) {
				case START:
					startTunnel(connection, tunnel);
					break;
				case STOP:
					stopTunnel(connection, tunnel);
					break;
				default:
					stopTunnel(connection, prevTunnel);
					startTunnel(connection, tunnel);
					break;
				}
			} catch (JSchException e) {
				status = -1;
				e.printStackTrace();
			}
		}
		return status;
	}
	
	public int startTunnelIfSessionConnected(Session session, Tunnel tunnel) {
		return updateTunnelIfSessionConnected(session, TunnelUpdateState.START, tunnel, null);
	}
	
	public int stopTunnelIfSessionConnected(Session session, Tunnel tunnel) {
		return updateTunnelIfSessionConnected(session, TunnelUpdateState.STOP, tunnel, null);
	}
	
	public int changeTunnelIfSessionConnected(Session session, Tunnel tunnel, Tunnel prevTunnel) {
		return updateTunnelIfSessionConnected(session, TunnelUpdateState.CHANGE, tunnel, prevTunnel);
	}

	/**
	 * Closes the connection of the session, also when it has already died, so that the next
	 * connect starts from scratch.
	 */
	public void disconnect(Session session) {
		clearTunnelExceptions(session);
		Connection connection = connections.remove(session);
		if (connection != null) {
			log.info("Disconnecting session: " + session);
			connection.close();
		}
	}

	/**
	 * Closes the connections that died without being disconnected (e.g. network loss) and returns
	 * their sessions.
	 */
	public List<Session> closeDeadConnections() {
		List<Session> lostSessions = new ArrayList<Session>();
		for (Map.Entry<Session, Connection> entry : connections.entrySet()) {
			Connection connection = entry.getValue();
			if (!connection.isConnected() && closeConnection(entry.getKey(), connection)) {
				log.warn("Session " + entry.getKey().getSessionName() + " has disconnected.");
				lostSessions.add(entry.getKey());
			}
		}
		return lostSessions;
	}

	private boolean closeConnection(Session session, Connection connection) {
		// Remove only this connection, a concurrent connect may already have registered a new one
		if (connections.remove(session, connection)) {
			connection.close();
			return true;
		}
		return false;
	}

	private void stopTunnel(Connection connection, Tunnel tunnel) throws JSchException {
		if (tunnel.getLocal()) {
			try {
				connection.jschSession.delPortForwardingL(tunnel.getLocalAddress(), tunnel.getLocalPort());
			} finally {
				connection.serverSocketFactory.closeSocket(tunnel.getLocalAddress(), tunnel.getLocalPort());
			}
		} else {
			connection.jschSession.delPortForwardingR(tunnel.getRemotePort());
		}
	}

	private void clearTunnelExceptions(Session session) {
		for (Iterator<Tunnel> i = session.getTunnels().iterator(); i.hasNext();) {
			Tunnel tunnel = i.next();
			tunnel.setException(null);
		}
	}

	public boolean isConnected(Session session) {
		Connection connection = connections.get(session);
		return connection != null && connection.isConnected();
	}
	
	public Exception getSessionException(Session session) {
		// Currently use keepAliveMsg
		//boolean hasError = false;
		Exception err = null;
		Connection connection = connections.get(session);
		if (connection != null ) {//&& !jschSession.isConnected()) {
			try {
				ChannelExec testChannel = (ChannelExec) connection.jschSession.openChannel("exec");
				testChannel.setCommand("true");
				testChannel.connect();
				testChannel.disconnect();
				//jschSession.sendKeepAliveMsg();
			} catch (Exception e) {
				err = e;
				//e.printStackTrace();
			}
			//hasError = true;
		}
		return err;
	}

	/**
	 * One SSH connection of a session: a new JSch session plus the sockets it uses.
	 */
	private static class Connection {

		final com.jcraft.jsch.Session jschSession;
		final TrackedSocketFactory socketFactory = new TrackedSocketFactory(TIMEOUT);
		final TrackedServerSocketFactory serverSocketFactory = new TrackedServerSocketFactory();

		Connection(com.jcraft.jsch.Session jschSession) {
			this.jschSession = jschSession;
			jschSession.setSocketFactory(socketFactory);
		}

		boolean isConnected() {
			// JSch notices a dead link through its keepalives, unless its threads are stuck writing to
			// the dead socket (full TCP send buffer). Then nothing is received any more at all.
			return jschSession.isConnected() && !socketFactory.isSilentFor(STALL_TIMEOUT);
		}

		void close() {
			// Close the TCP connection first. On a dead link JSch threads can be stuck writing to it
			// while holding the session lock, and jschSession.disconnect() would wait for them (on the
			// UI thread). Closing the socket makes those writes fail right away.
			socketFactory.closeSocket();
			jschSession.disconnect();
			serverSocketFactory.closeAll();
		}
	}

}

class SshLogger implements com.jcraft.jsch.Logger {
    static java.util.Hashtable<Integer, String> name = new java.util.Hashtable<Integer, String>();
    
    private Logger logger = Logger.getLogger(SshLogger.class.getSimpleName());
    
    public SshLogger(String filePath) {
		//this.logger = logger;
		try {
//			String jarPath = getClass()
//			          .getProtectionDomain()
//			          .getCodeSource()
//			          .getLocation()
//			          .toURI()
//			          .getPath();
			
			//FileHandler fh = new FileHandler(jarPath + "/" + "sshtunnel.log");
			FileHandler fh = new FileHandler(filePath);
			SimpleFormatter formatter = new SimpleFormatter();  
	        fh.setFormatter(formatter);  
			logger.addHandler(fh);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
    
    
    static{
      name.put(new Integer(DEBUG), "DEBUG: ");
      name.put(new Integer(INFO), "INFO: ");
      name.put(new Integer(WARN), "WARN: ");
      name.put(new Integer(ERROR), "ERROR: ");
      name.put(new Integer(FATAL), "FATAL: ");
    }
    
    public boolean isEnabled(int level){
      return true;
    }
    
    public void log(int level, String message){
//      System.err.print(name.get(level));
//      System.err.println(message);
    	switch (level) {
		case INFO:
			logger.info(message);
			break;
		case WARN:
			logger.warning(message);
			break;
		case ERROR:
			logger.severe(message);
			break;
		case FATAL:
			logger.severe(message);
			break;
		default:
			break;
		}
    }
 }


