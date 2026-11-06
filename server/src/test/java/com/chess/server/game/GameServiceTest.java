package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.chess.common.protocol.MessageType.*;
import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {
    private static final int COLOR = 1, STATUS = 2, TURN = 3, PLY = 4, FEN = 5;
    private static final int RESULT = 7, REASON = 8, MATCH = 9, LAST_MOVE = 11;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final RoomManager manager = new RoomManager();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private final GameService service = new GameService(manager, MatchEventWriter.disabled(), clock);

    private Message parsed(OutboundMessage output) {
        return codec.decode(output.getMessage());
    }

    private String create(String session) {
        Message reply = parsed(service.handle(session, "CREATE_ROOM|" + session).get(0));
        assertEquals(ROOM_CREATED, reply.getType());
        return reply.getField(0);
    }

    private String start(String white, String black) {
        String room = create(white);
        assertEquals(2, service.handle(black, "JOIN_ROOM|" + room + "|" + black).size());
        return room;
    }

    private List<OutboundMessage> move(String session, String room, String uci) {
        return service.handle(session, "MOVE|" + room + "|" + uci);
    }

    private Message state(String session, String room) {
        Message reply = parsed(service.handle(session, "GET_STATE|" + room).get(0));
        assertEquals(STATE, reply.getType());
        return reply;
    }

    private void reason(List<OutboundMessage> output, MessageType type, String expected) {
        assertEquals(1, output.size());
        Message reply = parsed(output.get(0));
        assertEquals(type, reply.getType());
        assertEquals(expected, reply.getField(reply.getFieldCount() - 1));
    }

    @Test
    void createsWaitingRoomAndStartsWithDifferentColors() {
        String room = create("white");
        assertTrue(room.matches("[A-Z0-9]{6}"));
        assertEquals("WAITING", state("white", room).getField(STATUS));
        assertEquals("WHITE", state("white", room).getField(COLOR));
        List<OutboundMessage> output = service.handle("black", "JOIN_ROOM|" + room + "|Đen");
        assertEquals(List.of("white", "black"), output.stream().map(OutboundMessage::getRecipientSessionId).toList());
        assertEquals("WHITE", parsed(output.get(0)).getField(COLOR));
        assertEquals("BLACK", parsed(output.get(1)).getField(COLOR));
        assertEquals("IN_PROGRESS", parsed(output.get(0)).getField(STATUS));
        assertEquals("Đen", parsed(output.get(0)).getField(13));
    }

    @Test
    void preventsSelfJoinOverfillingAndOneSessionInTwoActiveRooms() {
        String room = create("white");
        reason(service.handle("white", "JOIN_ROOM|" + room + "|Self"), ERROR, "ALREADY_IN_ROOM");
        service.handle("black", "JOIN_ROOM|" + room + "|Black");
        reason(service.handle("third", "JOIN_ROOM|" + room + "|Third"), ERROR, "ROOM_FULL");
        reason(service.handle("white", "CREATE_ROOM|Again"), ERROR, "ALREADY_IN_ROOM");
    }

    @Test
    void waitingRoomRejectsMoveAndResignation() {
        String room = create("white");
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_NOT_STARTED");
        reason(service.handle("white", "RESIGN|" + room), ERROR, "GAME_NOT_STARTED");
        assertEquals("0", state("white", room).getField(PLY));
    }

    @Test
    void rejectsWrongTurnIntruderAndIllegalMoveWithoutChangingState() {
        String room = start("white", "black");
        String before = codec.encode(state("white", room));
        reason(move("black", room, "e7e5"), MOVE_REJECTED, "NOT_YOUR_TURN");
        reason(move("intruder", room, "e2e4"), MOVE_REJECTED, "NOT_IN_ROOM");
        reason(move("white", room, "e2e5"), MOVE_REJECTED, "ILLEGAL_MOVE");
        assertEquals(before, codec.encode(state("white", room)));
    }

    @Test
    void acceptedMoveBroadcastsSameBoardAndExactlyOneHistoryRecord() {
        String room = start("white", "black");
        List<OutboundMessage> output = move("white", room, "e2e4");
        assertEquals(2, output.size());
        Message white = parsed(output.get(0));
        Message black = parsed(output.get(1));
        assertEquals(STATE, white.getType());
        assertEquals(white.getField(FEN), black.getField(FEN));
        assertEquals("1", white.getField(PLY));
        assertEquals("BLACK", white.getField(TURN));
        assertEquals("e2e4", white.getField(LAST_MOVE));
        List<OutboundMessage> history = service.handle("white", "GET_STATE|" + room);
        assertEquals(3, history.size());
        Message record = parsed(history.get(1));
        assertEquals(HISTORY, record.getType());
        assertEquals("1", record.getField(2));
        assertEquals("WHITE", record.getField(3));
        assertEquals("e2e4", record.getField(4));
        assertEquals(clock.instant().toString(), record.getField(5));
        assertEquals(white.getField(FEN), record.getField(6));
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "NOT_YOUR_TURN");
        assertEquals(3, service.handle("white", "GET_STATE|" + room).size());
    }

    @Test
    void refusesExtraFieldsInvalidMoveMissingRoomAndServerCommands() {
        String room = start("white", "black");
        reason(service.handle("white", "MOVE|" + room + "|e2e4|BLACK"), MOVE_REJECTED, "INVALID_ARGUMENTS");
        reason(service.handle("white", "MOVE|" + room + "|e2e4|forged-fen"), MOVE_REJECTED, "INVALID_ARGUMENTS");
        reason(move("white", room, "e2e4extra"), MOVE_REJECTED, "INVALID_MOVE_FORMAT");
        reason(move("white", "", "e2e4"), MOVE_REJECTED, "ROOM_ID_REQUIRED");
        reason(move("white", "bad", "e2e4"), MOVE_REJECTED, "INVALID_ROOM_ID");
        reason(move("white", "ZZZZZZ", "e2e4"), MOVE_REJECTED, "ROOM_NOT_FOUND");
        reason(service.handle("white", "GAME_END|" + room), ERROR, "UNSUPPORTED_COMMAND");
        assertEquals("0", state("white", room).getField(PLY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Name\t", "abcdefghijklmnopqrstuvwxyz0123456789abcde"})
    void rejectsInvalidPlayerNames(String name) {
        reason(service.handle("white", "CREATE_ROOM|" + name), ERROR, "INVALID_PLAYER_NAME");
        assertEquals(0, manager.roomCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE_ROOM|Name|Injected", "PING|extra", "JOIN_ROOM", "MOVE", "RESIGN|ABC234|extra", "GET_STATE|ABC234|1|extra"})
    void rejectsIncorrectArgumentCounts(String line) {
        reason(service.handle("white", line), line.startsWith("MOVE") ? MOVE_REJECTED : ERROR, "INVALID_ARGUMENTS");
        assertEquals(0, manager.roomCount());
    }

    @Test
    void malformedAndOversizedInputDoesNotBreakFollowingPing() {
        reason(service.handle("white", "CREATE_ROOM|Name\nInjected"), ERROR, "BAD_MESSAGE");
        reason(service.handle("white", "CREATE_ROOM|Name\r"), ERROR, "BAD_MESSAGE");
        reason(service.handle("white", "not-a-command"), ERROR, "BAD_MESSAGE");
        reason(service.handle("white", null), ERROR, "BAD_MESSAGE");
        reason(service.handle("white", "CREATE_ROOM|" + "ờ".repeat(6_000)), ERROR, "MESSAGE_TOO_LARGE");
        assertEquals("PONG", service.handle("white", "PING").get(0).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"white", "black"})
    void resignationEndsGameAndAllowsNextMatch(String resigning) {
        String room = start("white", "black");
        List<OutboundMessage> output = service.handle(resigning, "RESIGN|" + room);
        assertEquals(2, output.size());
        Message end = parsed(output.get(0));
        assertEquals(GAME_END, end.getType());
        assertEquals(resigning.equals("white") ? "BLACK_WIN" : "WHITE_WIN", end.getField(RESULT));
        assertEquals("RESIGNATION", end.getField(REASON));
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_FINISHED");
        String oldMatch = state("white", room).getField(MATCH);
        String next = create("white");
        assertNotEquals(oldMatch, state("white", next).getField(MATCH));
    }

    @Test
    void checkmateSendsFinalStateAndGameEndToBothPlayers() {
        String room = start("white", "black");
        move("white", room, "f2f3");
        move("black", room, "e7e5");
        move("white", room, "g2g4");
        List<OutboundMessage> output = move("black", room, "d8h4");
        assertEquals(List.of(STATE, STATE, GAME_END, GAME_END), output.stream().map(o -> parsed(o).getType()).toList());
        Message end = parsed(output.get(0));
        assertEquals("BLACK_WIN", end.getField(RESULT));
        assertEquals("CHECKMATE", end.getField(REASON));
        assertEquals("FINISHED", end.getField(STATUS));
        assertEquals("4", end.getField(PLY));
        assertEquals(6, service.handle("white", "GET_STATE|" + room).size());
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_FINISHED");
    }

    @Test
    void repetitionEndsInDraw() {
        String room = start("white", "black");
        for (int cycle = 0; cycle < 2; cycle++) {
            move("white", room, "g1f3");
            move("black", room, "g8f6");
            move("white", room, "f3g1");
            move("black", room, "f6g8");
        }
        assertEquals("DRAW", state("white", room).getField(RESULT));
        assertEquals("THREEFOLD_REPETITION", state("white", room).getField(REASON));
    }

    @Test
    void disconnectNotifiesOpponentAndCleansRoomsIdempotently() {
        String waiting = create("waiting");
        assertTrue(service.onDisconnect("waiting").isEmpty());
        assertEquals(0, manager.roomCount());
        reason(service.handle("other", "JOIN_ROOM|" + waiting + "|Other"), ERROR, "ROOM_NOT_FOUND");
        String room = start("white", "black");
        List<OutboundMessage> output = service.onDisconnect("black");
        assertEquals(List.of(OPPONENT_DISCONNECTED, GAME_END), output.stream().map(o -> parsed(o).getType()).toList());
        assertTrue(output.stream().allMatch(o -> o.getRecipientSessionId().equals("white")));
        assertEquals("WHITE_WIN", state("white", room).getField(RESULT));
        assertTrue(service.onDisconnect("black").isEmpty());
        service.onDisconnect("white");
        assertEquals(0, manager.roomCount());
    }

    @Test
    void twoRoomsHaveIndependentBoardsAndRouting() {
        String a = start("a-white", "a-black");
        String b = start("b-white", "b-black");
        String before = codec.encode(state("b-white", b));
        List<OutboundMessage> output = move("a-white", a, "e2e4");
        assertTrue(output.stream().allMatch(o -> o.getRecipientSessionId().startsWith("a-")));
        assertEquals(before, codec.encode(state("b-white", b)));
        assertEquals("1", state("a-white", a).getField(PLY));
    }

    @Test
    @Timeout(15)
    void simultaneousMovesCannotApplyTwice() throws Exception {
        String room = start("white", "black");
        var executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<OutboundMessage>>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(executor.submit(() -> { start.await(); return move("white", room, "e2e4"); }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) {
                if (parsed(future.get(10, TimeUnit.SECONDS).get(0)).getType() == STATE) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertEquals("1", state("white", room).getField(PLY));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesCapacityAndReleasesWaitingRooms() {
        GameService game = new GameService(new RoomManager(1), MatchEventWriter.disabled(), clock);
        game.handle("one", "CREATE_ROOM|One");
        reason(game.handle("two", "CREATE_ROOM|Two"), ERROR, "SERVER_BUSY");
        game.onDisconnect("one");
        assertEquals(ROOM_CREATED, parsed(game.handle("two", "CREATE_ROOM|Two").get(0)).getType());
    }

    @Test
    void historyPaginationHasNoDuplicatesAndDoesNotExposeMutableState() {
        String roomId = start("white", "black");
        GameRoom room = manager.getRoom(roomId);
        synchronized (room) {
            // Fixture dài để kiểm tra chia trang, không cần dựng một khai cuộc 105 nước.
            for (int ply = 1; ply <= 105; ply++) {
                room.history.add(new MoveRecord(room.id, room.matchId, ply, PlayerColor.WHITE,
                        "e2e4", clock.instant(), room.rules.getFen()));
            }
        }
        List<OutboundMessage> page1 = service.handle("white", "GET_STATE|" + roomId);
        assertEquals(102, page1.size());
        assertEquals(HISTORY_END, parsed(page1.get(101)).getType());
        assertEquals("101", parsed(page1.get(101)).getField(2));
        List<OutboundMessage> page2 = service.handle("white", "GET_STATE|" + roomId + "|101");
        assertEquals(7, page2.size());
        assertEquals("101", parsed(page2.get(1)).getField(2));
        assertEquals("105", parsed(page2.get(5)).getField(2));
        assertEquals("0", parsed(page2.get(6)).getField(2));
        List<OutboundMessage> empty = service.handle("white", "GET_STATE|" + roomId + "|106");
        assertEquals(2, empty.size());
        for (String invalid : List.of("-1", "0", "107", "abc", "1.5", "999999999999999999")) {
            reason(service.handle("white", "GET_STATE|" + roomId + "|" + invalid), ERROR, "INVALID_ARGUMENTS");
        }
        assertThrows(UnsupportedOperationException.class, page1::clear);
        assertEquals("105", state("white", roomId).getField(PLY));
    }

    @Test
    @Timeout(15)
    void lockingOneRoomDoesNotBlockMovesInAnotherRoom() throws Exception {
        String a = start("a-white", "a-black");
        String b = start("b-white", "b-black");
        GameRoom roomA = manager.getRoom(a);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        Future<List<OutboundMessage>> pendingA;
        try {
            synchronized (roomA) {
                pendingA = executor.submit(() -> {
                    started.countDown();
                    return move("a-white", a, "e2e4");
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var pendingB = executor.submit(() -> move("b-white", b, "d2d4"));
                assertEquals(STATE, parsed(pendingB.get(5, TimeUnit.SECONDS).get(0)).getType());
                assertFalse(pendingA.isDone());
            }
            assertEquals(STATE, parsed(pendingA.get(5, TimeUnit.SECONDS).get(0)).getType());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    void simultaneousJoinCannotAssignBlackToTwoSessions() throws Exception {
        String room = create("white");
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<OutboundMessage>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String session = "joiner-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.handle(session, "JOIN_ROOM|" + room + "|" + session);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) {
                List<OutboundMessage> output = future.get(5, TimeUnit.SECONDS);
                if (parsed(output.get(0)).getType() == GAME_START) {
                    accepted++;
                    assertEquals(2, output.size());
                } else {
                    reason(output, ERROR, "ROOM_FULL");
                }
            }
            assertEquals(1, accepted);
            assertEquals(1, manager.roomCount());
        } finally {
            executor.shutdownNow();
        }
    }
}
