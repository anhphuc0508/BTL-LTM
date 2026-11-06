package com.chess.server.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Một phòng chơi. Dùng synchronized (room) khi đọc hoặc sửa trạng thái bên trong. */
public final class GameRoom {
    public enum Status { WAITING, IN_PROGRESS, FINISHED }

    static final class Player {
        private final String sessionId;
        private final String name;
        private final PlayerColor color;

        Player(String sessionId, String name, PlayerColor color) {
            this.sessionId = sessionId;
            this.name = name;
            this.color = color;
        }

        public String getSessionId() { return sessionId; }
        public String getName() { return name; }
        public PlayerColor getColor() { return color; }

        public String sessionId() { return getSessionId(); }
        public String name() { return getName(); }
        public PlayerColor color() { return getColor(); }
    }

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
        return getId();
    }

    public String matchId() {
        return getMatchId();
    }

    public String getId() {
        return id;
    }

    public String getMatchId() {
        return matchId;
    }

    Player getPlayer(String sessionId) {
        if (white.getSessionId().equals(sessionId)) {
            return white;
        }
        if (black != null && black.getSessionId().equals(sessionId)) {
            return black;
        }
        throw new GameException("NOT_IN_ROOM");
    }

    List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();
        players.add(white);
        if (black != null) {
            players.add(black);
        }
        return Collections.unmodifiableList(players);
    }

    void finish(String result, String reason) {
        status = Status.FINISHED;
        this.result = result;
        endReason = reason;
        version++;
    }

    public synchronized List<MoveRecord> getMoveHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }
}
