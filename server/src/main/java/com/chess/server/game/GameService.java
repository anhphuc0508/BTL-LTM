package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Nhận yêu cầu và xử lý trận đấu. Bắt đầu đọc từ handle(), rồi handleMove(). */
public final class GameService {
    public static final int HISTORY_PAGE_SIZE = 100;
    private final RoomManager rooms;
    private final Clock clock;
    private final GameRequestReader requests = new GameRequestReader();
    private final GameResponseBuilder responses = new GameResponseBuilder();
    private final GameEventLogger events;

    public GameService() {
        this(new RoomManager(), new MatchEventWriter(Path.of("logs", "matches")), Clock.systemUTC());
    }

    public GameService(RoomManager rooms, MatchEventWriter matchWriter, Clock clock) {
        this.rooms = Objects.requireNonNull(rooms);
        this.clock = Objects.requireNonNull(clock);
        this.events = new GameEventLogger(Objects.requireNonNull(matchWriter), clock);
    }

    /** Người 1 gọi hàm này sau khi đọc Message từ socket. Session do Server gán. */
    public List<OutboundMessage> handle(String sessionId, Message request) {
        requests.validateSessionId(sessionId);
        Objects.requireNonNull(request, "request");

        try {
            MessageType type = request.getType();
            if (type == MessageType.CREATE_ROOM) {
                return handleCreateRoom(sessionId, request);
            }
            if (type == MessageType.JOIN_ROOM) {
                return handleJoinRoom(sessionId, request);
            }
            if (type == MessageType.MOVE) {
                return handleMove(sessionId, request);
            }
            if (type == MessageType.RESIGN) {
                return handleResign(sessionId, request);
            }
            if (type == MessageType.GET_STATE) {
                return handleGetState(sessionId, request);
            }
            if (type == MessageType.PING) {
                return handlePing(sessionId, request);
            }
            throw new GameException("UNSUPPORTED_COMMAND");
        } catch (GameException ex) {
            if (request.getType() == MessageType.MOVE) {
                events.rejectedMove(sessionId, request, ex.getCode());
            }
            return responses.error(sessionId, request, ex.getCode());
        }
    }

    private List<OutboundMessage> handleCreateRoom(String sessionId, Message request) {
        String playerName = requests.readCreatorName(request);

        // Khóa danh sách phòng trong lúc thay đổi thành viên và chuẩn bị phản hồi.
        synchronized (rooms) {
            GameRoom room = rooms.createRoom(sessionId, playerName);
            synchronized (room) {
                events.write("ROOM_CREATED", room, request.getRequestId(), null);
                return responses.roomResponse(sessionId, MessageType.ROOM_CREATED, request, room, 1);
            }
        }
    }

    private List<OutboundMessage> handleJoinRoom(String sessionId, Message request) {
        String playerName = requests.readPlayerName(request);
        String roomId = requests.readRoomId(request);

        synchronized (rooms) {
            GameRoom room = rooms.getRoom(roomId);
            synchronized (room) {
                rooms.joinRoom(sessionId, playerName, roomId);
                events.write("GAME_START", room, request.getRequestId(), null);
                return responses.broadcast(room, MessageType.GAME_START, request, sessionId, 1);
            }
        }
    }

    /** Ví dụ đầu vào: người chơi gửi nước e2e4. Đọc lần lượt các bước dưới đây. */
    private List<OutboundMessage> handleMove(String sessionId, Message request) {
        // 1. Lấy chuỗi nước đi và tìm phòng.
        String uci = requests.readMove(request);
        String roomId = requests.readRoomId(request);
        GameRoom room = rooms.getRoom(roomId);

        // Mỗi phòng có khóa riêng để hai luồng không cùng sửa bàn cờ/lượt đi.
        synchronized (room) {
            // 2. Người gửi phải thuộc phòng, và trận phải đang chơi.
            rooms.requireMember(room, sessionId);
            requirePlaying(room);
            GameRoom.Player player = room.getPlayer(sessionId);

            // 3. Kiểm tra đúng lượt.
            if (player.getColor() != room.rules.getSideToMove()) {
                throw new GameException("NOT_YOUR_TURN");
            }

            // 4. Nhờ thư viện kiểm tra luật. Chỉ nước hợp lệ mới thay đổi bàn cờ.
            if (!room.rules.applyMove(uci)) {
                throw new GameException("ILLEGAL_MOVE");
            }

            // 5. Lưu đúng một bản ghi cho nước vừa được chấp nhận.
            MoveRecord acceptedMove = new MoveRecord(room.id, room.matchId, room.history.size() + 1,
                    player.getColor(), uci, clock.instant(), room.rules.getFen());
            room.history.add(acceptedMove);
            room.version++;
            events.write("MOVE_ACCEPTED", room, request.getRequestId(), acceptedMove);

            // 6. Kiểm tra nước này có làm ván đấu kết thúc không.
            boolean gameEnded = finishIfNecessary(room, player, request);

            // 7. Chuẩn bị trạng thái mới cho cả hai Client. Người 1 sẽ gửi qua socket.
            int fromPly = responses.recentHistoryStart(room);
            List<OutboundMessage> output = new ArrayList<>();
            output.addAll(responses.broadcast(room, MessageType.STATE, request, sessionId, fromPly));
            if (gameEnded) {
                output.addAll(responses.broadcast(room, MessageType.GAME_END, request, sessionId, fromPly));
            }
            return Collections.unmodifiableList(output);
        }
    }

    private List<OutboundMessage> handleResign(String sessionId, Message request) {
        requests.requireEmptyPayload(request);
        String roomId = requests.readRoomId(request);
        GameRoom room = rooms.getRoom(roomId);

        synchronized (room) {
            rooms.requireMember(room, sessionId);
            requirePlaying(room);
            GameRoom.Player player = room.getPlayer(sessionId);
            PlayerColor winner = player.getColor().opposite();
            room.finish(winner.name() + "_WIN", "RESIGNATION");
            events.write("GAME_END", room, request.getRequestId(), null);
            int fromPly = responses.recentHistoryStart(room);
            return responses.broadcast(room, MessageType.GAME_END, request, sessionId, fromPly);
        }
    }

    private List<OutboundMessage> handleGetState(String sessionId, Message request) {
        int fromPly = requests.readHistoryStart(request);
        String roomId = requests.readRoomId(request);
        GameRoom room = rooms.getRoom(roomId);

        synchronized (room) {
            rooms.requireMember(room, sessionId);
            if (fromPly > room.history.size() + 1) {
                throw new GameException("INVALID_PAYLOAD");
            }
            return responses.roomResponse(sessionId, MessageType.STATE, request, room, fromPly);
        }
    }

    private List<OutboundMessage> handlePing(String sessionId, Message request) {
        requests.requireEmptyPayload(request);
        return responses.pong(sessionId, request);
    }

    /** Người 1 gọi khi EOF/timeout/lỗi I/O, sau khi dừng luồng đọc của session này. */
    public List<OutboundMessage> onDisconnect(String sessionId) {
        requests.validateSessionId(sessionId);

        synchronized (rooms) {
            GameRoom room = rooms.getRoomForSession(sessionId);
            if (room == null) {
                return Collections.emptyList();
            }
            synchronized (room) {
                List<OutboundMessage> output = Collections.emptyList();
                if (room.status == GameRoom.Status.IN_PROGRESS) {
                    GameRoom.Player departed = room.getPlayer(sessionId);
                    GameRoom.Player remaining;
                    if (departed.getColor() == PlayerColor.WHITE) {
                        remaining = room.black;
                    } else {
                        remaining = room.white;
                    }
                    room.finish(remaining.getColor().name() + "_WIN", "DISCONNECT");
                    events.write("GAME_END", room, null, null);
                    output = responses.disconnected(room, remaining);
                }
                rooms.removeSession(sessionId);
                return output;
            }
        }
    }

    private boolean finishIfNecessary(GameRoom room, GameRoom.Player mover, Message request) {
        ChessRulesAdapter.PositionStatus status = room.rules.getStatus();
        if (status == ChessRulesAdapter.PositionStatus.IN_PROGRESS) {
            return false;
        }

        String result = "DRAW";
        if (status == ChessRulesAdapter.PositionStatus.CHECKMATE) {
            result = mover.getColor().name() + "_WIN";
        }
        room.finish(result, status.name());
        events.write("GAME_END", room, request.getRequestId(), null);
        return true;
    }

    private void requirePlaying(GameRoom room) {
        if (room.status == GameRoom.Status.WAITING) {
            throw new GameException("GAME_NOT_STARTED");
        }
        if (room.status == GameRoom.Status.FINISHED) {
            throw new GameException("GAME_FINISHED");
        }
    }
}
