package com.googlecode.jsonrpc4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLException;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings({"unused", "WeakerAccess"})
public class StreamServerWithHeartbeats {

	private static final Logger logger = LoggerFactory.getLogger(StreamServerWithHeartbeats.class);

	private static final long SERVER_SOCKET_SO_TIMEOUT = 1000; // we make it 1 second

	private final ThreadPoolExecutor executor;
	private final ServerSocket serverSocket;
	private final JsonRpcBasicServer jsonRpcServer;
	private final AtomicBoolean isStarted = new AtomicBoolean(false);
	private final AtomicBoolean keepRunning = new AtomicBoolean(false);
	private final Set<Server> servers = new HashSet<>();
	private int maxClientErrors = 5;

	/**
	 * Creates a {@code StreamServer} with the given max number
	 * of threads.  A {@link ServerSocket} is created using the
	 * default {@link ServerSocketFactory} that lists on the
	 * given {@code port} and {@link InetAddress}.
	 *
	 * @param jsonRpcServer the {@link JsonRpcBasicServer} that will handleRequest requests
	 * @param maxThreads    the mac number of threads the server will spawn
	 * @param port          the port to listen on
	 * @param backlog       the {@link ServerSocket} backlog
	 * @param bindAddress   the address to listen on
	 * @throws IOException on error
	 */
	private StreamServerWithHeartbeats(JsonRpcBasicServer jsonRpcServer, int maxThreads, int port, int backlog, InetAddress bindAddress) throws IOException {
		this(jsonRpcServer, maxThreads, ServerSocketFactory.getDefault().createServerSocket(port, backlog, bindAddress));
	}

	/**
	 * Creates a {@code StreamServer} with the given max number
	 * of threads using the given {@link ServerSocket} to listen
	 * for client connections.
	 *
	 * @param jsonRpcServer the {@link JsonRpcBasicServer} that will handleRequest requests
	 * @param maxThreads    the mac number of threads the server will spawn
	 * @param serverSocket  the {@link ServerSocket} used for accepting client connections
	 */
	public StreamServerWithHeartbeats(JsonRpcBasicServer jsonRpcServer, int maxThreads, ServerSocket serverSocket) {
		this.jsonRpcServer = jsonRpcServer;
		this.serverSocket = serverSocket;
		executor = new ThreadPoolExecutor(maxThreads + 1, maxThreads + 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		jsonRpcServer.setRethrowExceptions(false);
	}
	
	/**
	 * Returns the current servers.
	 *
	 * @return the servers
	 */
	public Set<Server> getServers() {
		return Collections.unmodifiableSet(servers);
	}
	
	/**
	 * Starts the server.
	 */
	public void start() {
		if (tryToStart()) {
			throw new IllegalStateException("The StreamServer is already started");
		}
		logger.debug("StreamServer starting {}:{}", serverSocket.getInetAddress(), serverSocket.getLocalPort());
		keepRunning.set(true);
		executor.submit(new Server());
	}
	
	private boolean tryToStart() {
		return !isStarted.compareAndSet(false, true);
	}
	
	/**
	 * Stops the server thread.
	 *
	 * @throws InterruptedException if a graceful shutdown didn't happen
	 */
	public void stop() throws InterruptedException {
		if (!isStarted.get()) {
			throw new IllegalStateException("The StreamServer is not started");
		}
		stopServer();
		stopClients();
		closeSocket();
		try {
			waitForServerToTerminate();
			isStarted.set(false);
			stopServer();
		} catch (InterruptedException e) {
			logger.error("InterruptedException while waiting for termination", e);
			throw e;
		}
	}
	
	private void stopServer() {
		keepRunning.set(false);
	}
	
	private void stopClients() {
		executor.shutdownNow();
	}
	
	private void closeSocket() {
		try {
			serverSocket.close();
		} catch (IOException e) {
			logger.debug("Failed to close socket", e);
		}
	}
	
	private void waitForServerToTerminate() throws InterruptedException {
		if (!executor.isTerminated()) {
			executor.awaitTermination(2000 + SERVER_SOCKET_SO_TIMEOUT, TimeUnit.MILLISECONDS);
		}
	}
	
	/**
	 * Closes something quietly.
	 *
	 * @param c closable
	 */
	private void closeQuietly(Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Throwable t) {
				logger.warn("Error closing, ignoring", t);
			}
		}
	}
	
	/**
	 * @return the number of connected clients
	 */
	public int getNumberOfConnections() {
		return servers.size();
	}
	
	/**
	 * @return the maxClientErrors
	 */
	public int getMaxClientErrors() {
		return maxClientErrors;
	}
	
	/**
	 * @param maxClientErrors the maxClientErrors to set
	 */
	public void setMaxClientErrors(int maxClientErrors) {
		this.maxClientErrors = maxClientErrors;
	}
	
	/**
	 * @return the isStarted
	 */
	public boolean isStarted() {
		return isStarted.get();
	}

	private AtomicBoolean connectionInProgress = new AtomicBoolean(false);
	private AtomicLong listeningCounter = new AtomicLong(0);
	private AtomicLong connectedCounter = new AtomicLong(0);
	
	/**
	 * Server thread.
	 */
	public class Server implements Runnable {
		
		private int errors;
		private Throwable lastException;
		
		public int getNumberOfErrors() {
			return errors;
		}
		
		public Throwable getLastException() {
			return lastException;
		}
		
		/**
		 * {@inheritDoc}
		 */
		public void run() {
			ServerSocket serverSocket = StreamServerWithHeartbeats.this.serverSocket;
			Socket clientSocket = null;
			while (StreamServerWithHeartbeats.this.keepRunning.get()) {
				try {
					serverSocket.setSoTimeout((int) SERVER_SOCKET_SO_TIMEOUT);
					clientSocket = serverSocket.accept();
					logger.debug("Client connected: {}:{}", clientSocket.getInetAddress().getHostAddress(), clientSocket.getPort());
					// spawn a new Server for the next connection and break out of the server loop
					//executor.submit(new Server());
					break;
				} catch (SocketTimeoutException e) {
					handleSocketTimeoutException(e);
				} catch (SSLException sslException) {
					logger.error("SSLException while listening for clients, terminating", sslException);
					break;
				} catch (IOException ioe) {
					// this could be because the ServerSocket was closed
					if (SocketException.class.isInstance(ioe) && !keepRunning.get()) {
						break;
					}
					logger.error("Exception while listening for clients", ioe);
				}
			}
			if (clientSocket != null) {
				try {
					clientSocket.setSoTimeout((int) 1000L);
				} catch (SocketException e){
					logger.error("Exception when setting client socket timeout");
				}
				BufferedInputStream input;
				OutputStream output;
				try {
					input = new BufferedInputStream(clientSocket.getInputStream());
					output = clientSocket.getOutputStream();
					connectionInProgress.set(true);
				} catch (IOException e) {
					logger.error("Client socket failed", e);
					return;
				}
				
				servers.add(this);
				try {
					while (StreamServerWithHeartbeats.this.keepRunning.get()) {
						try {
							jsonRpcServer.handleRequest(input, output);
						} catch (Throwable t) {
							if (StreamEndedException.class.isInstance(t)) {
								logger.debug("Client disconnected: {}:{}", clientSocket.getInetAddress().getHostAddress(), clientSocket.getPort());
								System.out.println("Client disconnected: " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());
								break;
							} else if (SocketTimeoutException.class.isInstance(t)){
								//System.out.println("on_connected_heartbeat " + connectedCounter.getAndIncrement() + " thread = " + Thread.currentThread().getId());
								jsonRpcServer.handleHeartbeatConnected(output);
								continue;
							}
							errors++;
							lastException = t;
							if (errors < maxClientErrors) {
								logger.error("Exception while handling request", t);
							} else {
								logger.error("Closing client connection due to repeated errors", t);
								break;
							}
						}
					}
				} finally {
					connectionInProgress.set(false);
					servers.remove(this);
					closeQuietly(clientSocket);
					closeQuietly(input);
					closeQuietly(output);
					if (StreamServerWithHeartbeats.this.keepRunning.get()) {
						executor.submit(new Server());
					}
				}
			}
		}
		
		private void handleSocketTimeoutException(SocketTimeoutException e) {
			if (!connectionInProgress.get()) {
				jsonRpcServer.handleHeartbeatListening();
				System.out.println("MM2 listening " + listeningCounter.getAndIncrement() + " ...thread=" + Thread.currentThread().getId());
			}
		}
	}
}
