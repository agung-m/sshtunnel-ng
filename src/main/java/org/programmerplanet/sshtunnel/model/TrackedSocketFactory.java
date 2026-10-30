package org.programmerplanet.sshtunnel.model;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import com.jcraft.jsch.SocketFactory;

/**
 * Creates the TCP socket of an SSH connection and keeps a handle on it, so that a dead connection
 * can be closed even while JSch threads are blocked writing to it. Also records when data was last
 * received from the server.
 */
class TrackedSocketFactory implements SocketFactory {

	private final int connectTimeout;
	private volatile Socket socket;
	private volatile long lastReceived = System.nanoTime();

	TrackedSocketFactory(int connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException {
		Socket newSocket = new Socket();
		try {
			newSocket.connect(new InetSocketAddress(host, port), connectTimeout);
		} catch (IOException e) {
			newSocket.close();
			throw e;
		}
		socket = newSocket;
		lastReceived = System.nanoTime();
		return newSocket;
	}

	@Override
	public InputStream getInputStream(Socket socket) throws IOException {
		return new FilterInputStream(socket.getInputStream()) {
			@Override
			public int read() throws IOException {
				int b = super.read();
				if (b >= 0) {
					lastReceived = System.nanoTime();
				}
				return b;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int n = super.read(b, off, len);
				if (n > 0) {
					lastReceived = System.nanoTime();
				}
				return n;
			}
		};
	}

	@Override
	public OutputStream getOutputStream(Socket socket) throws IOException {
		return socket.getOutputStream();
	}

	/**
	 * Whether nothing arrived from the server for the given time and nothing is waiting to be read.
	 * Pending data means the server is alive and JSch is just not reading (for example because it is
	 * blocked writing to a local client that does not read).
	 */
	boolean isSilentFor(long millis) {
		if (System.nanoTime() - lastReceived < TimeUnit.MILLISECONDS.toNanos(millis)) {
			return false;
		}
		Socket current = socket;
		try {
			return current == null || current.getInputStream().available() == 0;
		} catch (IOException e) {
			return true;
		}
	}

	void closeSocket() {
		Socket current = socket;
		if (current != null) {
			try {
				current.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

}
