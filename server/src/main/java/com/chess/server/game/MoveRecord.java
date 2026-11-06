package com.chess.server.game;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** One accepted half-move (ply). UCI is used for display until SAN is supported. */
public record MoveRecord(String roomId, String matchId, int ply, PlayerColor color,
                         String uci, Instant acceptedAt, String fen) {
    ObjectNode toJson(ObjectNode target) {
        target.put("roomId", roomId);
        target.put("matchId", matchId);
        target.put("ply", ply);
        target.put("color", color.name());
        target.put("uci", uci);
        target.put("acceptedAt", acceptedAt.toString());
        target.put("fen", fen);
        return target;
    }
}
