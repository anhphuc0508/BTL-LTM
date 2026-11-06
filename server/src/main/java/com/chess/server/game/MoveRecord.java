package com.chess.server.game;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/** Lưu một nước đi đã hợp lệ. Ply là số thứ tự nước đi của từng bên, bắt đầu từ 1. */
public final class MoveRecord {
    private final String roomId;
    private final String matchId;
    private final int ply;
    private final PlayerColor color;
    private final String uci;
    private final Instant acceptedAt;
    private final String fen;

    public MoveRecord(String roomId, String matchId, int ply, PlayerColor color,
                      String uci, Instant acceptedAt, String fen) {
        this.roomId = roomId;
        this.matchId = matchId;
        this.ply = ply;
        this.color = color;
        this.uci = uci;
        this.acceptedAt = acceptedAt;
        this.fen = fen;
    }

    public String getRoomId() { return roomId; }
    public String getMatchId() { return matchId; }
    public int getPly() { return ply; }
    public PlayerColor getColor() { return color; }
    public String getUci() { return uci; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public String getFen() { return fen; }

    // Giữ cách gọi cũ để người dùng API ban đầu không phải đổi code.
    public String roomId() { return getRoomId(); }
    public String matchId() { return getMatchId(); }
    public int ply() { return getPly(); }
    public PlayerColor color() { return getColor(); }
    public String uci() { return getUci(); }
    public Instant acceptedAt() { return getAcceptedAt(); }
    public String fen() { return getFen(); }

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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MoveRecord)) {
            return false;
        }
        MoveRecord move = (MoveRecord) other;
        return ply == move.ply && color == move.color
                && Objects.equals(roomId, move.roomId) && Objects.equals(matchId, move.matchId)
                && Objects.equals(uci, move.uci) && Objects.equals(acceptedAt, move.acceptedAt)
                && Objects.equals(fen, move.fen);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(roomId);
        result = 31 * result + Objects.hashCode(matchId);
        result = 31 * result + ply;
        result = 31 * result + Objects.hashCode(color);
        result = 31 * result + Objects.hashCode(uci);
        result = 31 * result + Objects.hashCode(acceptedAt);
        result = 31 * result + Objects.hashCode(fen);
        return result;
    }

    @Override
    public String toString() {
        return "MoveRecord[roomId=" + roomId + ", matchId=" + matchId + ", ply=" + ply
                + ", color=" + color + ", uci=" + uci + ", acceptedAt=" + acceptedAt + ", fen=" + fen + "]";
    }
}
