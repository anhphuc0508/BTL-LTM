package com.chess.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Ghi nhật ký bằng chuỗi thuần túy, mỗi sự kiện một dòng. */
final class GameEventLogger {
    private static final Logger LOG = LoggerFactory.getLogger(GameService.class);
    private final MatchEventWriter writer;
    private final Clock clock;

    GameEventLogger(MatchEventWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    void write(String event, GameRoom room, MoveRecord move) {
        String uci = "";
        String color = "";
        if (move != null) {
            uci = move.getUci();
            color = move.getColor().name();
        }
        String result = "";
        String reason = "";
        if (room.result != null) {
            result = room.result;
            reason = room.endReason;
        }
        String line = event + "|" + room.id + "|" + room.matchId + "|" + clock.instant()
                + "|" + room.version + "|" + room.history.size() + "|" + color + "|" + uci
                + "|" + room.rules.getFen() + "|" + result + "|" + reason;
        LOG.info("event={} roomId={} matchId={} ply={} move={} result={} reason={}",
                event, room.id, room.matchId, room.history.size(), uci, result, reason);
        writer.write(room.matchId, line);
    }

    void rejectedMove(String sessionId, String roomId, String reason) {
        LOG.warn("event=MOVE_REJECTED roomId={} sessionId={} reason={}", roomId, sessionId, reason);
    }
}
