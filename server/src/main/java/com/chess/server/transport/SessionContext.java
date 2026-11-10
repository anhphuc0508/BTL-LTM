package com.chess.server.transport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Một kết nối có một hàng đợi và một luồng ghi riêng. */
public final class SessionContext {
    private static final Logger log = LoggerFactory.getLogger(SessionContext.class);
    private final String id;
    private final Socket socket;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(256);

    public SessionContext(String id, Socket socket) {
        this.id = id;
        this.socket = socket;
    }

    public String getId() { return id; }
    public Socket getSocket() { return socket; }

    public boolean enqueue(String message) {
        return !socket.isClosed() && outgoing.offer(message);
    }

    public void writeMessages() throws IOException, InterruptedException {
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        while (!socket.isClosed()) {
            String message = outgoing.poll(200, TimeUnit.MILLISECONDS);
            if (message == null) { continue; }
            writer.write(message);
            writer.write('\n');
            writer.flush();
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ex) {
            log.error("event=IO_ERROR sessionId={} reason=CLOSE_FAILED", id, ex);
        }
    }
}
