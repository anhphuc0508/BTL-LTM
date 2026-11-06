package com.chess.server.game;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameLoggingTest {
    @TempDir Path temp;
    private final ProtocolCodec codec = new ProtocolCodec();

    private String start(GameService game) {
        String room = game.handle("white", new Message(MessageType.CREATE_ROOM, "create-1", null,
                codec.newPayload().put("playerName", "White"))).get(0).message().roomId();
        game.handle("black", new Message(MessageType.JOIN_ROOM, "join-1", room,
                codec.newPayload().put("playerName", "Black")));
        return room;
    }

    @Test
    void sharedLogbackConfigurationWritesTechnicalLogWithoutConfigurationErrors() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setName("configuration-test");
        // A manually-created context needs the MDC adapter normally installed by the SLF4J provider.
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        context.putProperty("CHESS_LOG_DIR", temp.toString().replace('\\', '/'));
        JoranConfigurator config = new JoranConfigurator();
        config.setContext(context);
        try {
            config.doConfigure(Path.of("src", "main", "resources", "logback.xml").toFile());
            context.getLogger("game-fixture").info("event=GAME_START roomId=ABC234");
            assertFalse(context.getStatusManager().getCopyOfStatusList().stream()
                    .anyMatch(status -> status.getLevel() == Status.ERROR),
                    () -> context.getStatusManager().getCopyOfStatusList().toString());
            assertTrue(Files.readString(temp.resolve("server.log")).contains("event=GAME_START roomId=ABC234"));
        } finally {
            context.stop();
        }
    }

    @Test
    void archiveContainsOnlyAcceptedMovesAndFinalResult() throws Exception {
        GameService game = new GameService(new RoomManager(), new MatchEventWriter(temp), Clock.systemUTC());
        String room = start(game);
        game.handle("white", new Message(MessageType.MOVE, "bad-1", room, codec.newPayload().put("move", "e2e5")));
        game.handle("white", new Message(MessageType.MOVE, "move-1", room, codec.newPayload().put("move", "e2e4")));
        game.handle("black", new Message(MessageType.RESIGN, "end-1", room, null));
        try (var files = Files.list(temp)) {
            var paths = files.toList();
            assertEquals(1, paths.size());
            var lines = Files.readAllLines(paths.get(0));
            assertEquals(4, lines.size());
            JsonMapper json = new JsonMapper();
            assertEquals("ROOM_CREATED", json.readTree(lines.get(0)).path("event").asText());
            assertEquals("GAME_START", json.readTree(lines.get(1)).path("event").asText());
            var move = json.readTree(lines.get(2));
            assertEquals("MOVE_ACCEPTED", move.path("event").asText());
            assertEquals("e2e4", move.path("uci").asText());
            assertEquals(1, move.path("ply").asInt());
            assertEquals("WHITE_WIN", json.readTree(lines.get(3)).path("result").asText());
            assertFalse(String.join("", lines).contains("e2e5"));
        }
    }

    @Test
    void rejectedMoveCreatesWarningButNoHistory() {
        Logger logger = (Logger) LoggerFactory.getLogger(GameService.class);
        Level oldLevel = logger.getLevel();
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.INFO);
        try {
            GameService game = new GameService(new RoomManager(), MatchEventWriter.disabled(), Clock.systemUTC());
            String room = start(game);
            game.handle("white", new Message(MessageType.MOVE, "bad-1", room, codec.newPayload().put("move", "a1a4")));
            var warnings = capture.list.stream().filter(e -> e.getFormattedMessage().contains("event=MOVE_REJECTED")).toList();
            assertEquals(1, warnings.size());
            assertEquals(Level.WARN, warnings.get(0).getLevel());
            assertTrue(warnings.get(0).getFormattedMessage().contains("reason=ILLEGAL_MOVE"));
            assertTrue(warnings.get(0).getFormattedMessage().contains("requestId=bad-1"));
            assertFalse(capture.list.stream().anyMatch(e -> e.getFormattedMessage().contains("event=MOVE_ACCEPTED")));
            var state = game.handle("white", new Message(MessageType.GET_STATE, null, room, null)).get(0).message();
            assertEquals(0, state.payload().path("history").size());
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(oldLevel);
            capture.stop();
        }
    }

    @Test
    void diskFailureCannotTurnAcceptedMoveIntoRejection() throws Exception {
        Path fileInsteadOfDirectory = temp.resolve("not-a-directory");
        Files.writeString(fileInsteadOfDirectory, "fixture");
        Logger logger = (Logger) LoggerFactory.getLogger(MatchEventWriter.class);
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            GameService game = new GameService(new RoomManager(), new MatchEventWriter(fileInsteadOfDirectory), Clock.systemUTC());
            String room = start(game);
            var output = game.handle("white", new Message(MessageType.MOVE, "move-1", room,
                    codec.newPayload().put("move", "e2e4")));
            assertEquals(2, output.size());
            assertEquals(MessageType.STATE, output.get(0).message().type());
            assertEquals(1, output.get(0).message().payload().path("moveNumber").asInt());
        } finally {
            logger.setLevel(oldLevel);
        }
    }

    @Test
    void uniqueMatchIdsKeepArchivesSeparateAndRejectPathTraversal() throws Exception {
        MatchEventWriter writer = new MatchEventWriter(temp);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        writer.write(first, codec.newPayload().put("roomId", "ABC234"));
        writer.write(second, codec.newPayload().put("roomId", "ABC234"));
        assertTrue(Files.exists(temp.resolve(first + ".jsonl")));
        assertTrue(Files.exists(temp.resolve(second + ".jsonl")));
        assertThrows(IllegalArgumentException.class, () -> writer.write("../escape", codec.newPayload()));
    }
}
