package com.chess.server.game;

import java.util.ArrayList;
import java.util.List;

/** Mutable state is package-private and accessed only while synchronized on this room. */
public final class GameRoom {
    public enum Status { WAITING, IN_PROGRESS, FINISHED }

    record Player(String sessionId, String name, PlayerColor color) { }

    final String id;
    final String matchId;
    final Player white;
    Player black;
    final ChessRulesAdapter rules;
    final List<MoveRecord> history = new ArrayList<>();
    Status status = Status.WAITING;
    String result;
    String endReason;
    long version;

    GameRoom(String id, String matchId, String creatorSessionId, String name) {
        this.id = id;
        this.matchId = matchId;
        this.white = new Player(creatorSessionId, name, PlayerColor.WHITE);
        this.rules = new ChessRulesAdapter();
    }

    public String id() {
        return id;
    }

    public String matchId() {
        return matchId;
    }

    Player player(String sessionId) {
        if (white.sessionId().equals(sessionId)) {
            return white;
        }
        if (black != null && black.sessionId().equals(sessionId)) {
            return black;
        }
        throw new GameException("NOT_IN_ROOM");
    }

    List<Player> players() {
        return black == null ? List.of(white) : List.of(white, black);
    }

    void finish(String result, String reason) {
        status = Status.FINISHED;
        this.result = result;
        endReason = reason;
        version++;
    }

    public synchronized List<MoveRecord> getMoveHistory() {
        return List.copyOf(history);
    }
}
