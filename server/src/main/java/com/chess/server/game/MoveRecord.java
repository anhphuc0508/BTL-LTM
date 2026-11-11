package com.chess.server.game;

import java.time.Instant;

/** Một nước đi hợp lệ; ply là số thứ tự nước đi, bắt đầu từ 1. */
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
}
