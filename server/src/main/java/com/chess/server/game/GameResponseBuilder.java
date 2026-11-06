package com.chess.server.game;

import com.chess.common.protocol.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ghép các giá trị thành chuỗi phản hồi; thứ tự ô xem docs/protocol.md. */
final class GameResponseBuilder {
    List<OutboundMessage> roomResponse(String sessionId, MessageType type, GameRoom room) {
        String line = snapshot(type, room, room.getPlayer(sessionId));
        return Collections.singletonList(new OutboundMessage(sessionId, line));
    }

    List<OutboundMessage> broadcast(GameRoom room, MessageType type) {
        List<OutboundMessage> output = new ArrayList<>();
        for (GameRoom.Player player : room.getPlayers()) {
            output.add(new OutboundMessage(player.getSessionId(), snapshot(type, room, player)));
        }
        return Collections.unmodifiableList(output);
    }

    List<OutboundMessage> error(String sessionId, String reason) {
        return Collections.singletonList(new OutboundMessage(sessionId, "ERROR|" + reason));
    }

    List<OutboundMessage> rejectedMove(String sessionId, String roomId, String reason) {
        return Collections.singletonList(new OutboundMessage(sessionId,
                "MOVE_REJECTED|" + roomId + "|" + reason));
    }

    List<OutboundMessage> disconnected(GameRoom room, GameRoom.Player remaining) {
        List<OutboundMessage> output = new ArrayList<>();
        output.add(new OutboundMessage(remaining.getSessionId(),
                "OPPONENT_DISCONNECTED|" + room.id + "|" + room.matchId + "|" + room.version + "|DISCONNECT"));
        output.add(new OutboundMessage(remaining.getSessionId(),
                snapshot(MessageType.GAME_END, room, remaining)));
        return Collections.unmodifiableList(output);
    }

    List<OutboundMessage> stateWithHistory(String sessionId, GameRoom room, int fromPly) {
        List<OutboundMessage> output = new ArrayList<>(roomResponse(sessionId, MessageType.STATE, room));
        int end = Math.min(room.history.size(), fromPly - 1 + GameService.HISTORY_PAGE_SIZE);
        for (int i = fromPly - 1; i < end; i++) {
            MoveRecord move = room.history.get(i);
            String line = "HISTORY|" + room.id + "|" + room.matchId + "|" + move.getPly()
                    + "|" + move.getColor() + "|" + move.getUci() + "|" + move.getAcceptedAt()
                    + "|" + move.getFen();
            output.add(new OutboundMessage(sessionId, line));
        }
        int nextPly = 0;
        if (end < room.history.size()) {
            nextPly = end + 1;
        }
        output.add(new OutboundMessage(sessionId, "HISTORY_END|" + room.id + "|" + room.matchId + "|" + nextPly));
        return Collections.unmodifiableList(output);
    }

    private String snapshot(MessageType type, GameRoom room, GameRoom.Player player) {
        String blackName = "";
        if (room.black != null) {
            blackName = room.black.getName();
        }
        String lastMove = "";
        if (!room.history.isEmpty()) {
            lastMove = room.history.get(room.history.size() - 1).getUci();
        }
        String result = "";
        String reason = "";
        if (room.result != null) {
            result = room.result;
            reason = room.endReason;
        }
        return type.name() + "|" + room.id + "|" + player.getColor() + "|" + room.status
                + "|" + room.rules.getSideToMove() + "|" + room.history.size() + "|" + room.rules.getFen()
                + "|" + room.rules.isCheck() + "|" + result + "|" + reason
                + "|" + room.matchId + "|" + room.version + "|" + lastMove
                + "|" + room.white.getName() + "|" + blackName;
    }
}
