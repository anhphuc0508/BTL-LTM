package com.chess.server.bootstrap;

import com.chess.server.game.GameService;
import com.chess.server.transport.ClientHandler;
import com.chess.server.transport.ConnectionManager;
import com.chess.server.transport.SessionContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Điểm chạy Server. Đọc tiếp acceptClients(), rồi ClientHandler.run(). */
public final class GameServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GameServer.class);
    private final String host;
    private final int port;
    private final int timeoutMillis;
    private final ConnectionManager connections;
    private final ExecutorService workers;
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public GameServer(String host, int port, int timeoutMillis, int maxClients, GameService game) {
        if (port < 0 || port > 65535 || timeoutMillis < 1 || maxClients < 1 || maxClients > 256) {
            throw new IllegalArgumentException("Invalid port, timeout or maxClients (1..256)");
        }
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        connections = new ConnectionManager(game, maxClients);
        // Mỗi Client có một luồng đọc và một luồng ghi.
        workers = Executors.newFixedThreadPool(maxClients * 2);
    }

    public synchronized void start() throws IOException {
        if (listener != null || workers.isShutdown()) {
            throw new IllegalStateException("Server already started or closed");
        }
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(host, port));
        } catch (IOException ex) {
            listener.close();
            workers.shutdownNow();
            throw ex;
        }
        running = true;
        acceptThread = new Thread(this::acceptClients, "server-accept");
        acceptThread.start();
        log.info("event=SERVER_STARTED host={} port={}", host, listener.getLocalPort());
    }

    private void acceptClients() {
        try {
            while (running) {
                Socket socket = listener.accept();
                SessionContext session = new SessionContext(UUID.randomUUID().toString(), socket);
                try {
                    socket.setTcpNoDelay(true);
                    if (!connections.register(session)) {
                        log.warn("event=CONNECTION_REJECTED reason=SERVER_BUSY");
                        continue;
                    }
                    workers.execute(() -> writeToClient(session));
                    workers.execute(new ClientHandler(session, connections, timeoutMillis));
                } catch (IOException | RuntimeException ex) {
                    session.close();
                    connections.disconnect(session.getId(), "CLIENT_SETUP_FAILED");
                    if (running) { log.error("event=IO_ERROR reason=CLIENT_SETUP", ex); }
                }
            }
        } catch (IOException ex) {
            if (running) { log.error("event=IO_ERROR reason=ACCEPT_FAILED", ex); }
        } finally {
            close();
        }
    }

    private void writeToClient(SessionContext session) {
        try {
            session.writeMessages();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            if (!session.getSocket().isClosed()) {
                log.error("event=IO_ERROR sessionId={} reason=WRITE_FAILED", session.getId(), ex);
            }
        } finally {
            connections.disconnect(session.getId(), "WRITER_STOPPED");
        }
    }

    public int getPort() { return listener.getLocalPort(); }
    public int getConnectionCount() { return connections.getConnectionCount(); }

    @Override
    public void close() {
        synchronized (this) {
            if (workers.isShutdown()) { return; }
            running = false;
            if (listener != null) {
                try { listener.close(); }
                catch (IOException ex) { log.error("event=IO_ERROR reason=LISTENER_CLOSE", ex); }
            }
            connections.close();
            workers.shutdownNow();
        }
        try {
            if (acceptThread != null && Thread.currentThread() != acceptThread) { acceptThread.join(5_000); }
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("event=SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log.info("event=SERVER_STOPPED");
    }

    public static void main(String[] args) throws Exception {
        String host = setting("CHESS_HOST", "0.0.0.0");
        int port = Integer.parseInt(setting("CHESS_PORT", "5000"));
        int timeout = Integer.parseInt(setting("CHESS_TIMEOUT_MS", "120000"));
        int maxClients = Integer.parseInt(setting("CHESS_MAX_CLIENTS", "64"));
        GameServer server = new GameServer(host, port, timeout, maxClients, new GameService());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
        server.start();
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
