package com.chess.server.game;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Serializes membership changes; moves on different rooms use independent room locks. */
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

    synchronized <T> T createRoom(String sessionId, String name, Function<GameRoom, T> action) {
        ensureAvailable(sessionId);
        if (rooms.size() >= capacity) {
            throw new GameException("SERVER_BUSY");
        }
        String id;
        do {
            StringBuilder code = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            id = code.toString();
        } while (rooms.containsKey(id));
        GameRoom room = new GameRoom(id, UUID.randomUUID().toString(), sessionId, name);
        synchronized (room) {
            rooms.put(id, room);
            memberships.put(sessionId, id);
            return action.apply(room);
        }
    }

    synchronized <T> T joinRoom(String sessionId, String name, String roomId, Function<GameRoom, T> action) {
        GameRoom room = findRoom(roomId);
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
            return action.apply(room);
        }
    }

    <T> T inRoom(String sessionId, String roomId, Function<GameRoom, T> action) {
        GameRoom room = findRoom(roomId);
        synchronized (room) {
            if (!roomId.equals(memberships.get(sessionId))) {
                throw new GameException("NOT_IN_ROOM");
            }
            room.player(sessionId);
            return action.apply(room);
        }
    }

    synchronized <T> T disconnect(String sessionId, T empty, Function<GameRoom, T> action) {
        String roomId = memberships.get(sessionId);
        if (roomId == null) {
            return empty;
        }
        GameRoom room = rooms.get(roomId);
        synchronized (room) {
            T result = action.apply(room);
            memberships.remove(sessionId);
            removeIfUnused(room);
            return result;
        }
    }

    private GameRoom findRoom(String roomId) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            throw new GameException("ROOM_NOT_FOUND");
        }
        return room;
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
        boolean occupied = room.players().stream()
                .anyMatch(player -> room.id.equals(memberships.get(player.sessionId())));
        if (!occupied) {
            rooms.remove(room.id, room);
        }
    }

    public int roomCount() {
        return rooms.size();
    }
}
