package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tạo JSON phản hồi. Không thay đổi phòng, không gửi socket. */
final class GameResponseBuilder {
    private final ProtocolCodec codec = new ProtocolCodec();

    List<OutboundMessage> roomResponse(String sessionId, MessageType type, Message request,
                                       GameRoom room, int fromPly) {
        GameRoom.Player player = room.getPlayer(sessionId);
        ObjectNode payload = snapshot(room, player, fromPly);
        Message message = new Message(type, request.getRequestId(), room.id, payload);
        return Collections.singletonList(new OutboundMessage(sessionId, message));
    }

    List<OutboundMessage> broadcast(GameRoom room, MessageType type, Message request,
                                    String requesterSessionId, int fromPly) {
        List<OutboundMessage> output = new ArrayList<>();
        for (GameRoom.Player player : room.getPlayers()) {
            String requestId = null;
            if (player.getSessionId().equals(requesterSessionId)) {
                requestId = request.getRequestId();
            }
            ObjectNode payload = snapshot(room, player, fromPly);
            Message message = new Message(type, requestId, room.id, payload);
            output.add(new OutboundMessage(player.getSessionId(), message));
        }
        return Collections.unmodifiableList(output);
    }

    List<OutboundMessage> pong(String sessionId, Message request) {
        Message message = new Message(MessageType.PONG, request.getRequestId(), null, codec.newPayload());
        return Collections.singletonList(new OutboundMessage(sessionId, message));
    }

    List<OutboundMessage> error(String sessionId, Message request, String reason) {
        MessageType type = MessageType.ERROR;
        if (request.getType() == MessageType.MOVE) {
            type = MessageType.MOVE_REJECTED;
        }
        ObjectNode payload = codec.newPayload();
        payload.put("reason", reason);
        Message message = new Message(type, request.getRequestId(), request.getRoomId(), payload);
        return Collections.singletonList(new OutboundMessage(sessionId, message));
    }

    List<OutboundMessage> disconnected(GameRoom room, GameRoom.Player remaining) {
        ObjectNode notice = codec.newPayload();
        notice.put("matchId", room.matchId);
        notice.put("version", room.version);
        notice.put("reason", "DISCONNECT");
        Message disconnected = new Message(MessageType.OPPONENT_DISCONNECTED, null, room.id, notice);
        ObjectNode finalState = snapshot(room, remaining, recentHistoryStart(room));
        Message gameEnd = new Message(MessageType.GAME_END, null, room.id, finalState);

        List<OutboundMessage> output = new ArrayList<>();
        output.add(new OutboundMessage(remaining.getSessionId(), disconnected));
        output.add(new OutboundMessage(remaining.getSessionId(), gameEnd));
        return Collections.unmodifiableList(output);
    }

    int recentHistoryStart(GameRoom room) {
        return Math.max(1, room.history.size() - GameService.HISTORY_PAGE_SIZE + 1);
    }

    private ObjectNode snapshot(GameRoom room, GameRoom.Player player, int fromPly) {
        ObjectNode payload = codec.newPayload();
        payload.put("matchId", room.matchId);
        payload.put("version", room.version);
        payload.put("status", room.status.name());
        payload.put("yourColor", player.getColor().name());
        payload.put("whitePlayer", room.white.getName());
        if (room.black != null) {
            payload.put("blackPlayer", room.black.getName());
        }
        payload.put("fen", room.rules.getFen());
        payload.put("turn", room.rules.getSideToMove().name());
        payload.put("inCheck", room.rules.isCheck());
        payload.put("moveNumber", room.history.size());
        if (!room.history.isEmpty()) {
            MoveRecord lastMove = room.history.get(room.history.size() - 1);
            payload.put("lastMove", lastMove.getUci());
        }
        if (room.result != null) {
            payload.put("result", room.result);
            payload.put("reason", room.endReason);
        }

        // Mỗi lần gửi tối đa 100 nước. Client dùng nextHistoryPly để lấy trang tiếp theo.
        ArrayNode history = payload.putArray("history");
        int end = Math.min(room.history.size(), fromPly - 1 + GameService.HISTORY_PAGE_SIZE);
        for (int i = fromPly - 1; i < end; i++) {
            MoveRecord move = room.history.get(i);
            history.add(move.toJson(codec.newPayload()));
        }
        payload.put("historyFromPly", fromPly);
        if (end < room.history.size()) {
            payload.put("nextHistoryPly", end + 1);
        }
        return payload;
    }
}
