package com.chess.server.game;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
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
        String room = codec.decode(game.handle("white", "CREATE_ROOM|White").get(0).getMessage()).getField(0);
        game.handle("black", "JOIN_ROOM|" + room + "|Black");
        return room;
    }

    @Test
    void sharedLogbackConfigurationWritesTechnicalLogWithoutConfigurationErrors() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setName("configuration-test");
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
    void plainTextArchiveContainsOnlyAcceptedMovesAndFinalResult() throws Exception {
        GameService game = new GameService(new RoomManager(), new MatchEventWriter(temp), Clock.systemUTC());
        String room = start(game);
        game.handle("white", "MOVE|" + room + "|e2e5");
        game.handle("white", "MOVE|" + room + "|e2e4");
        game.handle("black", "RESIGN|" + room);
        try (var files = Files.list(temp)) {
            var paths = files.toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().endsWith(".log"));
            var lines = Files.readAllLines(paths.get(0));
            assertEquals(4, lines.size());
            assertEquals("ROOM_CREATED", lines.get(0).split("\\|", -1)[0]);
            assertEquals("GAME_START", lines.get(1).split("\\|", -1)[0]);
            String[] move = lines.get(2).split("\\|", -1);
            assertEquals(11, move.length);
            assertEquals("MOVE_ACCEPTED", move[0]);
            assertEquals(room, move[1]);
            assertEquals("1", move[5]);
            assertEquals("WHITE", move[6]);
            assertEquals("e2e4", move[7]);
            String[] end = lines.get(3).split("\\|", -1);
            assertEquals("WHITE_WIN", end[9]);
            assertEquals("RESIGNATION", end[10]);
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
            game.handle("white", "MOVE|" + room + "|a1a4");
            var warnings = capture.list.stream().filter(e -> e.getFormattedMessage().contains("event=MOVE_REJECTED")).toList();
            assertEquals(1, warnings.size());
            assertEquals(Level.WARN, warnings.get(0).getLevel());
            assertTrue(warnings.get(0).getFormattedMessage().contains("reason=ILLEGAL_MOVE"));
            assertTrue(warnings.get(0).getFormattedMessage().contains("sessionId=white"));
            assertFalse(capture.list.stream().anyMatch(e -> e.getFormattedMessage().contains("event=MOVE_ACCEPTED")));
            var state = game.handle("white", "GET_STATE|" + room);
            assertEquals(2, state.size());
            assertEquals("0", codec.decode(state.get(0).getMessage()).getField(4));
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
            var output = game.handle("white", "MOVE|" + room + "|e2e4");
            assertEquals(2, output.size());
            var reply = codec.decode(output.get(0).getMessage());
            assertEquals(MessageType.STATE, reply.getType());
            assertEquals("1", reply.getField(4));
        } finally {
            logger.setLevel(oldLevel);
        }
    }

    @Test
    void uniqueMatchIdsKeepArchivesSeparateAndRejectPathTraversal() throws Exception {
        MatchEventWriter writer = new MatchEventWriter(temp);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        writer.write(first, "ROOM_CREATED|ABC234");
        writer.write(second, "ROOM_CREATED|ABC234");
        assertTrue(Files.exists(temp.resolve(first + ".log")));
        assertTrue(Files.exists(temp.resolve(second + ".log")));
        assertThrows(IllegalArgumentException.class, () -> writer.write("../escape", "event"));
        assertThrows(IllegalArgumentException.class, () -> writer.write(first, "event\ninjected"));
    }
}
