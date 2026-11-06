package com.chess.server.game;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Quản lý danh sách phòng và ghi nhớ mỗi session đang thuộc phòng nào. */
public final class RoomManager {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> memberships = new ConcurrentHashMap<>();
    private final int capacity;

    public RoomManager() {
        this(10_000);
    }

    public RoomManager(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    // GameService giữ khóa rooms xuyên suốt tạo/vào/rời phòng và chuẩn bị phản hồi.
    synchronized GameRoom createRoom(String sessionId, String name) {
        ensureAvailable(sessionId);
        if (rooms.size() >= capacity) {
            throw new GameException("SERVER_BUSY");
        }

        String id = newRoomId();
        GameRoom room = new GameRoom(id, UUID.randomUUID().toString(), sessionId, name);
        rooms.put(id, room);
        memberships.put(sessionId, id);
        return room;
    }

    synchronized GameRoom joinRoom(String sessionId, String name, String roomId) {
        GameRoom room = getRoom(roomId);
        synchronized (room) {
            if (room.status == GameRoom.Status.FINISHED) {
                throw new GameException("GAME_FINISHED");
            }
            if (room.black != null) {
                throw new GameException("ROOM_FULL");
            }
            ensureAvailable(sessionId);
            room.black = new GameRoom.Player(sessionId, name, PlayerColor.BLACK);
            memberships.put(sessionId, roomId);
            room.status = GameRoom.Status.IN_PROGRESS;
            room.version++;
            return room;
        }
    }

    synchronized GameRoom getRoom(String roomId) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            throw new GameException("ROOM_NOT_FOUND");
        }
        return room;
    }

    // Gọi bên trong synchronized (room). Đọc ConcurrentHashMap không lấy khóa rooms.
    // Nhờ vậy luồng đang giữ khóa một phòng không chờ ngược lại khóa quản lý phòng.
    void requireMember(GameRoom room, String sessionId) {
        String currentRoomId = memberships.get(sessionId);
        if (!room.id.equals(currentRoomId) || rooms.get(room.id) != room) {
            throw new GameException("NOT_IN_ROOM");
        }
        room.getPlayer(sessionId);
    }

    synchronized GameRoom getRoomForSession(String sessionId) {
        String roomId = memberships.get(sessionId);
        if (roomId == null) {
            return null;
        }
        return rooms.get(roomId);
    }

    synchronized void removeSession(String sessionId) {
        String roomId = memberships.get(sessionId);
        if (roomId == null) {
            return;
        }
        GameRoom room = rooms.get(roomId);
        synchronized (room) {
            memberships.remove(sessionId);
            removeIfUnused(room);
        }
    }

    private String newRoomId() {
        String id;
        do {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                int index = random.nextInt(ALPHABET.length());
                code.append(ALPHABET.charAt(index));
            }
            id = code.toString();
        } while (rooms.containsKey(id));
        return id;
    }

    private void ensureAvailable(String sessionId) {
        String oldId = memberships.get(sessionId);
        if (oldId == null) {
            return;
        }
        GameRoom oldRoom = rooms.get(oldId);
        synchronized (oldRoom) {
            if (oldRoom.status != GameRoom.Status.FINISHED) {
                throw new GameException("ALREADY_IN_ROOM");
            }
            memberships.remove(sessionId);
            removeIfUnused(oldRoom);
        }
    }

    private void removeIfUnused(GameRoom room) {
        for (GameRoom.Player player : room.getPlayers()) {
            if (room.id.equals(memberships.get(player.getSessionId()))) {
                return;
            }
        }
        rooms.remove(room.id, room);
    }

    public int roomCount() {
        return rooms.size();
    }
}
