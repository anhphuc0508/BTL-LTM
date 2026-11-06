package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.ProtocolCodec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Ghi game event vào technical log và archive riêng của từng ván. */
final class GameEventLogger {
    // Giữ tên logger như bản đầu để cấu hình logging đang dùng vẫn hoạt động.
    private static final Logger LOG = LoggerFactory.getLogger(GameService.class);
    private final MatchEventWriter writer;
    private final Clock clock;
    private final ProtocolCodec codec = new ProtocolCodec();

    GameEventLogger(MatchEventWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    void write(String event, GameRoom room, String requestId, MoveRecord move) {
        ObjectNode entry = codec.newPayload();
        entry.put("event", event);
        entry.put("roomId", room.id);
        entry.put("matchId", room.matchId);
        entry.put("at", clock.instant().toString());
        entry.put("version", room.version);
        entry.put("ply", room.history.size());
        if (requestId != null) {
            entry.put("requestId", requestId);
        }
        String uci = null;
        if (move != null) {
            move.toJson(entry);
            uci = move.getUci();
        }
        if (room.result != null) {
            entry.put("result", room.result);
            entry.put("reason", room.endReason);
        }
        LOG.info("event={} roomId={} matchId={} requestId={} ply={} move={} result={} reason={}",
                event, room.id, room.matchId, requestId, room.history.size(), uci, room.result, room.endReason);
        writer.write(room.matchId, entry);
    }

    void rejectedMove(String sessionId, Message request, String reason) {
        LOG.warn("event=MOVE_REJECTED roomId={} requestId={} sessionId={} reason={}",
                request.getRoomId(), request.getRequestId(), sessionId, reason);
    }
}
