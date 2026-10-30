package org.programmerplanet.sshtunnel.ui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.programmerplanet.sshtunnel.model.ConnectionException;
import org.programmerplanet.sshtunnel.model.ConnectionManager;
import org.programmerplanet.sshtunnel.model.Session;

/**
 * 
 * @author <a href="agungm@outlook.com">Mulya Agung</a>
 */

public class SessionConnectionMonitor implements Runnable {

	private static final Log log = LogFactory.getLog(SessionConnectionMonitor.class);

	private static final int DEF_MONITOR_INTERVAL = 5000;
	// Wait after a failed reconnect attempt. An attempt is only a TCP connect while the network is down,
	// so retrying often is cheap and brings the tunnel back soon after the network is.
	private static final long RECONNECT_DELAY = 5000;
	private static final SessionConnectionMonitor INSTANCE = new SessionConnectionMonitor();

	private final Object lock = new Object();
	private Thread thread;
	private volatile boolean threadStopped;
	private int monitorInterval;
	private SshTunnelComposite sshTunnelComposite;
	private volatile Shell shell;
	// Sessions whose connection was lost (not closed by the user) and that are being reconnected
	private final Map<Session, Reconnect> reconnects = new ConcurrentHashMap<Session, Reconnect>();

	public SessionConnectionMonitor() {
		this(DEF_MONITOR_INTERVAL);
	}

	public SessionConnectionMonitor(int monitorInterval) {
		threadStopped = false;
		this.monitorInterval = monitorInterval;
	}

	public void run() {
		if (log.isWarnEnabled()) {
			log.warn("Connection monitor is now running..");
		}
		while (!threadStopped) {
			try {
				// Checks every open connection, whether it was opened by Connect, Connect All or the tray menu
				final List<Session> lostSessions = ConnectionManager.getInstance().closeDeadConnections();
				for (Session s : lostSessions) {
					reconnects.put(s, new Reconnect());
				}
				if (sshTunnelComposite != null && !lostSessions.isEmpty()) {
					Display.getDefault().asyncExec(new Runnable() {
						public void run() {
							for (Session s : lostSessions) {
								sshTunnelComposite.showDisconnectedMessage(s);
							}
							sshTunnelComposite.connectionStatusChanged();
						}
					});
				}
				startReconnects();
			} catch (RuntimeException e) {
				// Keep the monitor running
				log.error("Error while checking connections", e);
			}
			try {
				Thread.sleep(monitorInterval);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private void startReconnects() {
		long now = System.currentTimeMillis();
		for (Map.Entry<Session, Reconnect> entry : reconnects.entrySet()) {
			final Session session = entry.getKey();
			final Reconnect reconnect = entry.getValue();
			if (reconnect.running || now < reconnect.nextAttempt) {
				continue;
			}
			if (ConnectionManager.getInstance().isConnected(session)) {
				// Connected again by the user in the meantime
				reconnects.remove(session, reconnect);
				continue;
			}
			reconnect.running = true;
			// Own thread, a connect attempt can take up to the connect timeout
			Thread attempt = new Thread(new Runnable() {
				public void run() {
					reconnect(session, reconnect);
				}
			}, "Reconnect " + session.getSessionName());
			attempt.setDaemon(true);
			attempt.start();
		}
	}

	private void reconnect(final Session session, Reconnect reconnect) {
		boolean connected = false;
		Exception failure = null;
		try {
			ConnectionManager.getInstance().connect(session, shell);
			connected = ConnectionManager.getInstance().isConnected(session);
		} catch (ConnectionException e) {
			failure = e;
		} catch (RuntimeException e) {
			failure = e;
		} finally {
			reconnect.nextAttempt = System.currentTimeMillis() + RECONNECT_DELAY;
			reconnect.running = false;
		}
		if (reconnect.cancelled) {
			// The user disconnected the session while this attempt was running
			if (connected) {
				ConnectionManager.getInstance().disconnect(session);
			}
			return;
		}
		if (connected) {
			reconnects.remove(session, reconnect);
			log.warn("Session " + session.getSessionName() + " has been reconnected.");
			notifyUser(session, null);
		} else if (failure != null && !isRetryable(failure)) {
			reconnects.remove(session, reconnect);
			final String reason = getOriginatingCause(failure).getMessage();
			log.warn("Giving up reconnecting session " + session.getSessionName() + ": " + reason);
			notifyUser(session, reason);
		} else if (failure != null) {
			log.info("Reconnecting session " + session.getSessionName() + " failed, retrying: "
					+ getOriginatingCause(failure).getMessage());
		}
	}

	private void notifyUser(final Session session, final String failureReason) {
		if (sshTunnelComposite == null) {
			return;
		}
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				if (failureReason == null) {
					sshTunnelComposite.showReconnectedMessage(session);
				} else {
					sshTunnelComposite.showReconnectFailedMessage(session, failureReason);
				}
				sshTunnelComposite.connectionStatusChanged();
			}
		});
	}

	/**
	 * Network problems are retried until the network is back. Authentication and host key problems
	 * are not: retrying would not help and could lock the account.
	 */
	static boolean isRetryable(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && (message.contains("Auth fail") || message.contains("Auth cancel")
					|| message.contains("HostKey") || message.contains("invalid privatekey"))) {
				return false;
			}
		}
		return true;
	}

	private static Throwable getOriginatingCause(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * Stops reconnecting the session, e.g. because the user disconnected it.
	 */
	public void cancelReconnect(Session session) {
		Reconnect reconnect = reconnects.remove(session);
		if (reconnect != null) {
			reconnect.cancelled = true;
		}
	}

	public boolean isReconnecting(Session session) {
		return reconnects.containsKey(session);
	}

	public boolean isReconnectingAny() {
		return !reconnects.isEmpty();
	}

	public void setSshTunnelComposite(SshTunnelComposite sshTunnelComposite) {
		this.sshTunnelComposite = sshTunnelComposite;
		// Parent of the password/host key prompts of reconnects (getShell() must run on the UI thread)
		this.shell = sshTunnelComposite.getShell();
	}

	public void startMonitor() {
		synchronized (lock) {
			if (thread == null) {
				thread = new Thread(this);
				threadStopped = false;
				thread.start();
			}
		}
	}

	public void setThreadStopped(boolean stop) {
		threadStopped = stop;
	}

	public void stopMonitor() {
		synchronized (lock) {
			if (thread != null) {
				threadStopped = true;
				thread = null;
				
				if (log.isWarnEnabled()) {
					log.warn("Connection monitor is stopped.");
				}
			}
		}
	}

	public static SessionConnectionMonitor getInstance() {
		return INSTANCE;
	}

	private static class Reconnect {
		volatile long nextAttempt;
		volatile boolean running;
		volatile boolean cancelled;
	}

}
