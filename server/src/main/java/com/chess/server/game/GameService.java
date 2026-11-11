package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.chess.common.protocol.ProtocolException;

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
    private final ProtocolCodec codec = new ProtocolCodec();

    public GameService() {
        this(new RoomManager(), new MatchEventWriter(Path.of("logs", "matches")), Clock.systemUTC());
    }

    public GameService(RoomManager rooms, MatchEventWriter matchWriter, Clock clock) {
        this.rooms = Objects.requireNonNull(rooms);
        this.clock = Objects.requireNonNull(clock);
        this.events = new GameEventLogger(Objects.requireNonNull(matchWriter), clock);
    }

    /** Người 1 đưa thẳng dòng đọc từ socket vào đây. Ví dụ MOVE|ABC234|e2e4. */
    public List<OutboundMessage> handle(String sessionId, String line) {
        requests.validateSessionId(sessionId);
        Message request;
        try {
            request = codec.decode(line);
        } catch (ProtocolException ex) {
            return responses.error(sessionId, ex.getCode());
        }

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
                // Chỉ đưa mã phòng đã hợp lệ vào log/phản hồi.
                String roomId = "";
                if (request.getFieldCount() > 0 && request.getField(0).matches("[A-Z0-9]{6}")) {
                    roomId = request.getField(0);
                }
                events.rejectedMove(sessionId, roomId, ex.getCode());
                return responses.rejectedMove(sessionId, roomId, ex.getCode());
            }
            return responses.error(sessionId, ex.getCode());
        }
    }

    private List<OutboundMessage> handleCreateRoom(String sessionId, Message request) {
        String playerName = requests.readCreatorName(request);

        // Khóa danh sách phòng trong lúc thay đổi thành viên và chuẩn bị phản hồi.
        synchronized (rooms) {
            GameRoom room = rooms.createRoom(sessionId, playerName);
            synchronized (room) {
                events.write("ROOM_CREATED", room, null);
                return responses.roomResponse(sessionId, MessageType.ROOM_CREATED, room);
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
                events.write("GAME_START", room, null);
                return responses.broadcast(room, MessageType.GAME_START);
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
            events.write("MOVE_ACCEPTED", room, acceptedMove);

            // 6. Kiểm tra nước này có làm ván đấu kết thúc không.
            boolean gameEnded = finishIfNecessary(room, player);

            // 7. Chuẩn bị trạng thái mới cho cả hai Client. Người 1 sẽ gửi qua socket.
            List<OutboundMessage> output = new ArrayList<>();
            output.addAll(responses.broadcast(room, MessageType.STATE));
            if (gameEnded) {
                output.addAll(responses.broadcast(room, MessageType.GAME_END));
            }
            return Collections.unmodifiableList(output);
        }
    }

    private List<OutboundMessage> handleResign(String sessionId, Message request) {
        requests.requireFieldCount(request, 1);
        String roomId = requests.readRoomId(request);
        GameRoom room = rooms.getRoom(roomId);

        synchronized (room) {
            rooms.requireMember(room, sessionId);
            requirePlaying(room);
            GameRoom.Player player = room.getPlayer(sessionId);
            PlayerColor winner = player.getColor().opposite();
            room.finish(winner.name() + "_WIN", "RESIGNATION");
            events.write("GAME_END", room, null);
            return responses.broadcast(room, MessageType.GAME_END);
        }
    }

    private List<OutboundMessage> handleGetState(String sessionId, Message request) {
        int fromPly = requests.readHistoryStart(request);
        String roomId = requests.readRoomId(request);
        GameRoom room = rooms.getRoom(roomId);

        synchronized (room) {
            rooms.requireMember(room, sessionId);
            if (fromPly > room.history.size() + 1) {
                throw new GameException("INVALID_ARGUMENTS");
            }
            return responses.stateWithHistory(sessionId, room, fromPly);
        }
    }

    private List<OutboundMessage> handlePing(String sessionId, Message request) {
        requests.requireFieldCount(request, 0);
        return Collections.singletonList(new OutboundMessage(sessionId, "PONG"));
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
                    events.write("GAME_END", room, null);
                    output = responses.disconnected(room, remaining);
                }
                rooms.removeSession(sessionId);
                return output;
            }
        }
    }

    private boolean finishIfNecessary(GameRoom room, GameRoom.Player mover) {
        ChessRulesAdapter.PositionStatus status = room.rules.getStatus();
        if (status == ChessRulesAdapter.PositionStatus.IN_PROGRESS) {
            return false;
        }

        String result = "DRAW";
        if (status == ChessRulesAdapter.PositionStatus.CHECKMATE) {
            result = mover.getColor().name() + "_WIN";
        }
        room.finish(result, status.name());
        events.write("GAME_END", room, null);
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
