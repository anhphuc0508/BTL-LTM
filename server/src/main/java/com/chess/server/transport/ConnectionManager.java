package com.chess.server.transport;

import com.chess.server.game.GameService;
import com.chess.server.game.OutboundMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Quản lý session và chuyển phản hồi của GameService đến đúng hàng đợi. */
public final class ConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);
    private final Map<String, SessionContext> sessions = new HashMap<>();
    private final GameService game;
    private final int maxClients;
    private boolean closed;

    public ConnectionManager(GameService game, int maxClients) {
        this.game = game;
        this.maxClients = maxClients;
    }

    public synchronized boolean register(SessionContext session) {
        if (closed || sessions.size() >= maxClients) {
            session.close();
            return false;
        }
        sessions.put(session.getId(), session);
        log.info("event=CONNECT sessionId={}", session.getId());
        return true;
    }

    // Giữ chung khóa từ xử lý lệnh đến đưa vào hàng đợi để phản hồi không đảo thứ tự.
    // Luồng ghi socket chạy riêng, không giữ khóa này hay khóa phòng.
    public synchronized void handle(String sessionId, String line) {
        if (!sessions.containsKey(sessionId)) { return; }
        List<OutboundMessage> replies = game.handle(sessionId, line);
        for (OutboundMessage reply : replies) {
            if (reply.getMessage().equals("ERROR|BAD_MESSAGE")) {
                log.warn("event=BAD_MESSAGE sessionId={}", sessionId);
            }
        }
        route(replies);
    }

    private void route(List<OutboundMessage> replies) {
        List<String> failed = new ArrayList<>();
        for (OutboundMessage reply : replies) {
            SessionContext recipient = sessions.get(reply.getRecipientSessionId());
            if (recipient != null && !recipient.enqueue(reply.getMessage())) {
                failed.add(recipient.getId());
            }
        }
        for (String sessionId : failed) {
            disconnect(sessionId, "SEND_QUEUE_FULL");
        }
    }

    public synchronized void disconnect(String sessionId, String reason) {
        SessionContext session = sessions.remove(sessionId);
        if (session == null) { return; }
        session.close();
        log.info("event=DISCONNECT sessionId={} reason={}", sessionId, reason);
        route(game.onDisconnect(sessionId));
    }

    public synchronized int getConnectionCount() { return sessions.size(); }

    public synchronized void close() {
        closed = true;
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            disconnect(sessionId, "SERVER_SHUTDOWN");
        }
    }
}
