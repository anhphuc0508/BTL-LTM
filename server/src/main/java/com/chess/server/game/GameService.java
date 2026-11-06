package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Authoritative application service. It never reads/writes a socket or trusts a client-provided color/FEN. */
public final class GameService {
    public static final int HISTORY_PAGE_SIZE = 100;
    private static final Logger LOG = LoggerFactory.getLogger(GameService.class);
    private final RoomManager rooms;
    private final MatchEventWriter matchWriter;
    private final Clock clock;
    private final ProtocolCodec codec = new ProtocolCodec();

    public GameService() {
        this(new RoomManager(), new MatchEventWriter(Path.of("logs", "matches")), Clock.systemUTC());
    }

    public GameService(RoomManager rooms, MatchEventWriter matchWriter, Clock clock) {
        this.rooms = Objects.requireNonNull(rooms);
        this.matchWriter = Objects.requireNonNull(matchWriter);
        this.clock = Objects.requireNonNull(clock);
    }

    /** sessionId is assigned by the transport, never copied from the client's JSON payload. */
    public List<OutboundMessage> handle(String sessionId, Message request) {
        validateSessionId(sessionId);
        Objects.requireNonNull(request, "request");
        try {
            return switch (request.type()) {
                case CREATE_ROOM -> {
                    fields(request.payload(), Set.of("playerName"));
                    if (request.roomId() != null) {
                        throw new GameException("INVALID_PAYLOAD");
                    }
                    String name = playerName(request.payload());
                    yield rooms.createRoom(sessionId, name, room -> {
                        logEvent("ROOM_CREATED", room, request.requestId(), null);
                        return List.of(response(sessionId, MessageType.ROOM_CREATED, request, room,
                                snapshot(room, room.white, 1)));
                    });
                }
                case JOIN_ROOM -> {
                    fields(request.payload(), Set.of("playerName"));
                    String name = playerName(request.payload());
                    yield rooms.joinRoom(sessionId, name, requireRoomId(request), room -> {
                        logEvent("GAME_START", room, request.requestId(), null);
                        return broadcast(room, MessageType.GAME_START, request, sessionId, 1);
                    });
                }
                case MOVE -> {
                    fields(request.payload(), Set.of("move"));
                    String move = requiredText(request.payload(), "move");
                    if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
                        throw new GameException("INVALID_MOVE_FORMAT");
                    }
                    yield rooms.inRoom(sessionId, requireRoomId(request), room -> move(room, sessionId, request, move));
                }
                case RESIGN -> {
                    fields(request.payload(), Set.of());
                    yield rooms.inRoom(sessionId, requireRoomId(request), room -> {
                        requirePlaying(room);
                        PlayerColor winner = room.player(sessionId).color().opposite();
                        room.finish(winner.name() + "_WIN", "RESIGNATION");
                        logEvent("GAME_END", room, request.requestId(), null);
                        return broadcast(room, MessageType.GAME_END, request, sessionId, recentHistoryStart(room));
                    });
                }
                case GET_STATE -> {
                    fields(request.payload(), Set.of("fromPly"));
                    int fromPly = historyStart(request.payload());
                    yield rooms.inRoom(sessionId, requireRoomId(request), room -> {
                        if (fromPly > room.history.size() + 1) {
                            throw new GameException("INVALID_PAYLOAD");
                        }
                        return List.of(response(sessionId, MessageType.STATE, request, room,
                                snapshot(room, room.player(sessionId), fromPly)));
                    });
                }
                case PING -> {
                    fields(request.payload(), Set.of());
                    yield List.of(new OutboundMessage(sessionId,
                            new Message(MessageType.PONG, request.requestId(), null, codec.newPayload())));
                }
                default -> throw new GameException("UNSUPPORTED_COMMAND");
            };
        } catch (GameException ex) {
            MessageType type = request.type() == MessageType.MOVE ? MessageType.MOVE_REJECTED : MessageType.ERROR;
            ObjectNode payload = codec.newPayload().put("reason", ex.code());
            if (type == MessageType.MOVE_REJECTED) {
                // Only a fixed error code and validated identifiers are logged, never raw payload/name/move.
                LOG.warn("event=MOVE_REJECTED roomId={} requestId={} sessionId={} reason={}",
                        request.roomId(), request.requestId(), sessionId, ex.code());
            }
            return List.of(new OutboundMessage(sessionId,
                    new Message(type, request.requestId(), request.roomId(), payload)));
        }
    }

    /** Call once from the transport's EOF/error/timeout cleanup, after stopping that session's input loop. */
    public List<OutboundMessage> onDisconnect(String sessionId) {
        validateSessionId(sessionId);
        return rooms.disconnect(sessionId, List.of(), room -> {
            if (room.status != GameRoom.Status.IN_PROGRESS) {
                return List.of();
            }
            GameRoom.Player departed = room.player(sessionId);
            GameRoom.Player remaining = departed.color() == PlayerColor.WHITE ? room.black : room.white;
            room.finish(remaining.color().name() + "_WIN", "DISCONNECT");
            logEvent("GAME_END", room, null, null);
            ObjectNode snapshot = snapshot(room, remaining, recentHistoryStart(room));
            return List.of(
                    new OutboundMessage(remaining.sessionId(), new Message(MessageType.OPPONENT_DISCONNECTED,
                            null, room.id, codec.newPayload().put("matchId", room.matchId)
                            .put("version", room.version).put("reason", "DISCONNECT"))),
                    new OutboundMessage(remaining.sessionId(), new Message(MessageType.GAME_END,
                            null, room.id, snapshot)));
        });
    }

    private List<OutboundMessage> move(GameRoom room, String sessionId, Message request, String uci) {
        requirePlaying(room);
        GameRoom.Player player = room.player(sessionId);
        if (player.color() != room.rules.sideToMove()) {
            throw new GameException("NOT_YOUR_TURN");
        }
        if (!room.rules.applyMove(uci)) {
            throw new GameException("ILLEGAL_MOVE");
        }
        MoveRecord record = new MoveRecord(room.id, room.matchId, room.history.size() + 1,
                player.color(), uci, clock.instant(), room.rules.getFen());
        room.history.add(record);
        room.version++;
        logEvent("MOVE_ACCEPTED", room, request.requestId(), record);

        ChessRulesAdapter.PositionStatus status = room.rules.getStatus();
        boolean ended = status != ChessRulesAdapter.PositionStatus.IN_PROGRESS;
        if (ended) {
            String result = status == ChessRulesAdapter.PositionStatus.CHECKMATE ? player.color().name() + "_WIN" : "DRAW";
            room.finish(result, status.name());
            logEvent("GAME_END", room, request.requestId(), null);
        }
        List<OutboundMessage> output = new ArrayList<>(broadcast(room, MessageType.STATE, request, sessionId, recentHistoryStart(room)));
        if (ended) {
            output.addAll(broadcast(room, MessageType.GAME_END, request, sessionId, recentHistoryStart(room)));
        }
        return List.copyOf(output);
    }

    private List<OutboundMessage> broadcast(GameRoom room, MessageType type, Message request, String requesterSessionId, int fromPly) {
        List<OutboundMessage> output = new ArrayList<>();
        for (GameRoom.Player player : room.players()) {
            // Correlation identifiers belong to the requesting connection, not the opponent's pending requests.
            String requestId = player.sessionId().equals(requesterSessionId) ? request.requestId() : null;
            output.add(new OutboundMessage(player.sessionId(), new Message(type, requestId, room.id,
                    snapshot(room, player, fromPly))));
        }
        return List.copyOf(output);
    }

    private OutboundMessage response(String sessionId, MessageType type, Message request, GameRoom room, ObjectNode payload) {
        return new OutboundMessage(sessionId, new Message(type, request.requestId(), room.id, payload));
    }

    private ObjectNode snapshot(GameRoom room, GameRoom.Player player, int fromPly) {
        ObjectNode payload = codec.newPayload();
        payload.put("matchId", room.matchId);
        payload.put("version", room.version);
        payload.put("status", room.status.name());
        payload.put("yourColor", player.color().name());
        payload.put("whitePlayer", room.white.name());
        if (room.black != null) {
            payload.put("blackPlayer", room.black.name());
        }
        payload.put("fen", room.rules.getFen());
        payload.put("turn", room.rules.sideToMove().name());
        payload.put("inCheck", room.rules.isCheck());
        payload.put("moveNumber", room.history.size());
        if (!room.history.isEmpty()) {
            payload.put("lastMove", room.history.get(room.history.size() - 1).uci());
        }
        if (room.result != null) {
            payload.put("result", room.result);
            payload.put("reason", room.endReason);
        }
        ArrayNode history = payload.putArray("history");
        int end = Math.min(room.history.size(), fromPly - 1 + HISTORY_PAGE_SIZE);
        for (int i = fromPly - 1; i < end; i++) {
            history.add(room.history.get(i).toJson(codec.newPayload()));
        }
        payload.put("historyFromPly", fromPly);
        if (end < room.history.size()) {
            payload.put("nextHistoryPly", end + 1);
        }
        return payload;
    }

    private void logEvent(String event, GameRoom room, String requestId, MoveRecord move) {
        ObjectNode entry = codec.newPayload().put("event", event).put("roomId", room.id)
                .put("matchId", room.matchId).put("at", clock.instant().toString())
                .put("version", room.version).put("ply", room.history.size());
        if (requestId != null) {
            entry.put("requestId", requestId);
        }
        if (move != null) {
            move.toJson(entry);
        }
        if (room.result != null) {
            entry.put("result", room.result).put("reason", room.endReason);
        }
        LOG.info("event={} roomId={} matchId={} requestId={} ply={} move={} result={} reason={}",
                event, room.id, room.matchId, requestId, room.history.size(),
                move == null ? null : move.uci(), room.result, room.endReason);
        matchWriter.write(room.matchId, entry);
    }

    private static int recentHistoryStart(GameRoom room) {
        return Math.max(1, room.history.size() - HISTORY_PAGE_SIZE + 1);
    }

    private static void requirePlaying(GameRoom room) {
        if (room.status == GameRoom.Status.WAITING) {
            throw new GameException("GAME_NOT_STARTED");
        }
        if (room.status == GameRoom.Status.FINISHED) {
            throw new GameException("GAME_FINISHED");
        }
    }

    private static String requireRoomId(Message request) {
        if (request.roomId() == null) {
            throw new GameException("ROOM_ID_REQUIRED");
        }
        return request.roomId();
    }

    private static String playerName(ObjectNode payload) {
        String rawName = requiredText(payload, "playerName");
        String name = rawName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 40
                || rawName.codePoints().anyMatch(Character::isISOControl)) {
            throw new GameException("INVALID_PLAYER_NAME");
        }
        return name;
    }

    private static String requiredText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new GameException("INVALID_PAYLOAD");
        }
        return value.textValue();
    }

    private static int historyStart(ObjectNode payload) {
        JsonNode value = payload.get("fromPly");
        if (value == null) {
            return 1;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new GameException("INVALID_PAYLOAD");
        }
        return value.intValue();
    }

    private static void fields(ObjectNode payload, Set<String> allowed) {
        payload.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new GameException("INVALID_PAYLOAD");
            }
        });
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid server-generated sessionId");
        }
    }
}
