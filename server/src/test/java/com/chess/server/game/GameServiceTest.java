package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.chess.common.protocol.MessageType.*;
import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {
    private final ProtocolCodec codec = new ProtocolCodec();
    private final RoomManager manager = new RoomManager();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private final GameService service = new GameService(manager, MatchEventWriter.disabled(), clock);

    private Message request(MessageType type, String room, ObjectNode payload) {
        return new Message(type, "req-1", room, payload);
    }

    private String create(String session) {
        Message result = service.handle(session, request(CREATE_ROOM, null,
                codec.newPayload().put("playerName", session))).get(0).message();
        assertEquals(ROOM_CREATED, result.type());
        return result.roomId();
    }

    private String start(String white, String black) {
        String room = create(white);
        assertEquals(2, service.handle(black, request(JOIN_ROOM, room,
                codec.newPayload().put("playerName", black))).size());
        return room;
    }

    private List<OutboundMessage> move(String session, String room, String uci) {
        return service.handle(session, request(MOVE, room, codec.newPayload().put("move", uci)));
    }

    private ObjectNode state(String session, String room) {
        Message message = service.handle(session, request(GET_STATE, room, null)).get(0).message();
        assertEquals(STATE, message.type());
        return message.payload();
    }

    private void reason(List<OutboundMessage> output, MessageType type, String reason) {
        assertEquals(1, output.size());
        assertEquals(type, output.get(0).message().type());
        assertEquals(reason, output.get(0).message().payload().path("reason").asText());
    }

    @Test
    void createsWaitingRoomAndStartsWithDifferentColors() {
        String room = create("white");
        assertTrue(room.matches("[A-Z0-9]{6}"));
        assertEquals("WAITING", state("white", room).path("status").asText());
        assertEquals("WHITE", state("white", room).path("yourColor").asText());
        var output = service.handle("black", request(JOIN_ROOM, room, codec.newPayload().put("playerName", "Đen")));
        assertEquals(List.of("white", "black"), output.stream().map(OutboundMessage::recipientSessionId).toList());
        assertEquals("WHITE", output.get(0).message().payload().path("yourColor").asText());
        assertEquals("BLACK", output.get(1).message().payload().path("yourColor").asText());
        assertNull(output.get(0).message().requestId());
        assertEquals("req-1", output.get(1).message().requestId());
        assertEquals("IN_PROGRESS", output.get(0).message().payload().path("status").asText());
    }

    @Test
    void preventsSelfJoinOverfillingAndOneSessionInTwoActiveRooms() {
        String room = create("white");
        reason(service.handle("white", request(JOIN_ROOM, room, codec.newPayload().put("playerName", "Self"))), ERROR, "ALREADY_IN_ROOM");
        service.handle("black", request(JOIN_ROOM, room, codec.newPayload().put("playerName", "Black")));
        reason(service.handle("third", request(JOIN_ROOM, room, codec.newPayload().put("playerName", "Third"))), ERROR, "ROOM_FULL");
        reason(service.handle("white", request(CREATE_ROOM, null, codec.newPayload().put("playerName", "Again"))), ERROR, "ALREADY_IN_ROOM");
    }

    @Test
    void waitingRoomRejectsMoveAndResignation() {
        String room = create("white");
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_NOT_STARTED");
        reason(service.handle("white", request(RESIGN, room, null)), ERROR, "GAME_NOT_STARTED");
        assertEquals(0, state("white", room).path("history").size());
    }

    @Test
    void rejectsWrongTurnIntruderAndIllegalMoveWithoutChangingState() {
        String room = start("white", "black");
        ObjectNode before = state("white", room);
        reason(move("black", room, "e7e5"), MOVE_REJECTED, "NOT_YOUR_TURN");
        reason(move("intruder", room, "e2e4"), MOVE_REJECTED, "NOT_IN_ROOM");
        reason(move("white", room, "e2e5"), MOVE_REJECTED, "ILLEGAL_MOVE");
        assertEquals(before, state("white", room));
    }

    @Test
    void acceptedMoveBroadcastsSameStateAndExactlyOneHistoryRecord() {
        String room = start("white", "black");
        var output = move("white", room, "e2e4");
        assertEquals(2, output.size());
        ObjectNode white = output.get(0).message().payload();
        ObjectNode black = output.get(1).message().payload();
        assertEquals(white.path("fen"), black.path("fen"));
        assertEquals(white.path("history"), black.path("history"));
        assertEquals(1, white.path("moveNumber").asInt());
        assertEquals("BLACK", white.path("turn").asText());
        assertEquals(1, white.path("history").size());
        assertEquals("e2e4", white.path("history").get(0).path("uci").asText());
        assertEquals(clock.instant().toString(), white.path("history").get(0).path("acceptedAt").asText());
        assertNull(output.get(1).message().requestId());
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "NOT_YOUR_TURN");
        assertEquals(1, state("white", room).path("history").size());
    }

    @Test
    void refusesForgedColorFenUnknownCommandsAndMissingRoom() {
        String room = start("white", "black");
        reason(service.handle("white", request(MOVE, room,
                codec.newPayload().put("move", "e2e4").put("color", "BLACK"))), MOVE_REJECTED, "INVALID_PAYLOAD");
        reason(service.handle("white", request(MOVE, room,
                codec.newPayload().put("move", "e2e4").put("fen", "forged"))), MOVE_REJECTED, "INVALID_PAYLOAD");
        reason(move("white", room, "e2e4extra"), MOVE_REJECTED, "INVALID_MOVE_FORMAT");
        reason(move("white", null, "e2e4"), MOVE_REJECTED, "ROOM_ID_REQUIRED");
        reason(move("white", "ZZZZZZ", "e2e4"), MOVE_REJECTED, "ROOM_NOT_FOUND");
        reason(service.handle("white", request(GAME_END, room, null)), ERROR, "UNSUPPORTED_COMMAND");
        assertEquals(0, state("white", room).path("moveNumber").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Name\nInjected", "Name\r", "Name\t", "abcdefghijklmnopqrstuvwxyz0123456789abcde"})
    void rejectsInvalidPlayerNames(String name) {
        reason(service.handle("white", request(CREATE_ROOM, null,
                codec.newPayload().put("playerName", name))), ERROR, "INVALID_PLAYER_NAME");
        assertEquals(0, manager.roomCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"white", "black"})
    void resignationEndsGameAndAllowsNextMatch(String resigning) {
        String room = start("white", "black");
        var output = service.handle(resigning, request(RESIGN, room, null));
        assertEquals(2, output.size());
        assertEquals(GAME_END, output.get(0).message().type());
        assertEquals(resigning.equals("white") ? "BLACK_WIN" : "WHITE_WIN",
                output.get(0).message().payload().path("result").asText());
        assertEquals("RESIGNATION", output.get(0).message().payload().path("reason").asText());
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_FINISHED");
        String oldMatch = state("white", room).path("matchId").asText();
        String next = create("white");
        assertNotEquals(oldMatch, state("white", next).path("matchId").asText());
    }

    @Test
    void checkmateSendsFinalStateAndGameEndToBothPlayers() {
        String room = start("white", "black");
        move("white", room, "f2f3");
        move("black", room, "e7e5");
        move("white", room, "g2g4");
        var output = move("black", room, "d8h4");
        assertEquals(List.of(STATE, STATE, GAME_END, GAME_END), output.stream().map(m -> m.message().type()).toList());
        ObjectNode finalState = output.get(0).message().payload();
        assertEquals("BLACK_WIN", finalState.path("result").asText());
        assertEquals("CHECKMATE", finalState.path("reason").asText());
        assertEquals("FINISHED", finalState.path("status").asText());
        assertEquals(4, finalState.path("history").size());
        reason(move("white", room, "e2e4"), MOVE_REJECTED, "GAME_FINISHED");
    }

    @Test
    void repetitionEndsInAutomaticMvpDraw() {
        String room = start("white", "black");
        for (int cycle = 0; cycle < 2; cycle++) {
            move("white", room, "g1f3");
            move("black", room, "g8f6");
            move("white", room, "f3g1");
            move("black", room, "f6g8");
        }
        assertEquals("DRAW", state("white", room).path("result").asText());
        assertEquals("THREEFOLD_REPETITION", state("white", room).path("reason").asText());
    }

    @Test
    void disconnectNotifiesOpponentAndCleansRoomsIdempotently() {
        String waiting = create("waiting");
        assertTrue(service.onDisconnect("waiting").isEmpty());
        assertEquals(0, manager.roomCount());
        reason(service.handle("other", request(JOIN_ROOM, waiting,
                codec.newPayload().put("playerName", "Other"))), ERROR, "ROOM_NOT_FOUND");
        String room = start("white", "black");
        var output = service.onDisconnect("black");
        assertEquals(List.of(OPPONENT_DISCONNECTED, GAME_END), output.stream().map(m -> m.message().type()).toList());
        assertTrue(output.stream().allMatch(m -> m.recipientSessionId().equals("white")));
        assertEquals("WHITE_WIN", state("white", room).path("result").asText());
        assertTrue(service.onDisconnect("black").isEmpty());
        service.onDisconnect("white");
        assertEquals(0, manager.roomCount());
    }

    @Test
    void twoRoomsHaveIndependentBoardsAndRouting() {
        String a = start("a-white", "a-black");
        String b = start("b-white", "b-black");
        ObjectNode before = state("b-white", b);
        var output = move("a-white", a, "e2e4");
        assertTrue(output.stream().allMatch(m -> m.recipientSessionId().startsWith("a-")));
        assertEquals(before, state("b-white", b));
        assertEquals(1, state("a-white", a).path("moveNumber").asInt());
    }

    @Test
    @Timeout(15)
    void simultaneousMovesCannotApplyTwice() throws Exception {
        String room = start("white", "black");
        var executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<List<OutboundMessage>>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> { start.await(); return move("white", room, "e2e4"); });
            }
            List<Future<List<OutboundMessage>>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS).get(0).message().type() == STATE) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(1, state("white", room).path("history").size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesCapacityAndReleasesWaitingRooms() {
        RoomManager limited = new RoomManager(1);
        GameService game = new GameService(limited, MatchEventWriter.disabled(), clock);
        game.handle("one", request(CREATE_ROOM, null, codec.newPayload().put("playerName", "One")));
        reason(game.handle("two", request(CREATE_ROOM, null,
                codec.newPayload().put("playerName", "Two"))), ERROR, "SERVER_BUSY");
        game.onDisconnect("one");
        assertEquals(ROOM_CREATED, game.handle("two", request(CREATE_ROOM, null,
                codec.newPayload().put("playerName", "Two"))).get(0).message().type());
    }

    @Test
    void historyPaginationHasNoDuplicatesAndDoesNotExposeMutableState() {
        String roomId = start("white", "black");
        manager.inRoom("white", roomId, room -> {
            // Build a long archive fixture without relying on a contrived 101-ply legal opening.
            for (int ply = 1; ply <= 105; ply++) {
                room.history.add(new MoveRecord(room.id, room.matchId, ply, PlayerColor.WHITE,
                        "e2e4", clock.instant(), room.rules.getFen()));
            }
            return null;
        });
        var page1 = state("white", roomId);
        assertEquals(100, page1.path("history").size());
        assertEquals(101, page1.path("nextHistoryPly").asInt());
        var page2 = service.handle("white", request(GET_STATE, roomId,
                codec.newPayload().put("fromPly", 101))).get(0).message().payload();
        assertEquals(5, page2.path("history").size());
        assertEquals(101, page2.path("history").get(0).path("ply").asInt());
        assertFalse(page2.has("nextHistoryPly"));
        reason(service.handle("white", request(GET_STATE, roomId,
                codec.newPayload().put("fromPly", -1))), ERROR, "INVALID_PAYLOAD");
        reason(service.handle("white", request(GET_STATE, roomId,
                codec.newPayload().put("fromPly", 107))), ERROR, "INVALID_PAYLOAD");
        page1.removeAll();
        assertEquals(105, state("white", roomId).path("moveNumber").asInt());
    }
}
