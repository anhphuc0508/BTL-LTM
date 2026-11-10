package com.chess.server.transport;

import com.chess.common.protocol.ProtocolCodec;
import com.chess.server.bootstrap.GameServer;
import com.chess.server.game.GameService;
import com.chess.server.game.MatchEventWriter;
import com.chess.server.game.RoomManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Dùng Server thật thay vì TCP harness của phần game. */
@Timeout(20)
class GameServerIntegrationTest {
    private GameServer startServer(int timeout, int maxClients) throws IOException {
        GameService game = new GameService(new RoomManager(), MatchEventWriter.disabled(), Clock.systemUTC());
        GameServer server = new GameServer("127.0.0.1", 0, timeout, maxClients, game);
        server.start();
        return server;
    }

    @Test
    void fourClientsPlayInSeparateRoomsAndDisconnect() throws Exception {
        try (GameServer server = startServer(5000, 8);
             Peer white = new Peer(server); Peer black = new Peer(server);
             Peer otherWhite = new Peer(server); Peer otherBlack = new Peer(server)) {
            String room = createGame(white, black);
            String otherRoom = createGame(otherWhite, otherBlack);
            assertNotEquals(room, otherRoom);
            white.send("MOVE|" + room + "|e2e4\n");
            String[] state = white.receive().split("\\|", -1);
            assertEquals("STATE", state[0]);
            assertEquals("1", state[5]);
            assertEquals(state[6], black.receive().split("\\|", -1)[6]);
            white.send("MOVE|" + room + "|d2d4\n");
            assertEquals("MOVE_REJECTED|" + room + "|NOT_YOUR_TURN", white.receive());
            otherWhite.send("GET_STATE|" + otherRoom + "\n");
            assertEquals("0", otherWhite.receive().split("\\|", -1)[5]);
            assertTrue(otherWhite.receive().startsWith("HISTORY_END|"));
            black.close();
            assertTrue(white.receive().startsWith("OPPONENT_DISCONNECTED|" + room));
            assertTrue(white.receive().startsWith("GAME_END|" + room));
            otherWhite.send("PING\n");
            assertEquals("PONG", otherWhite.receive());
        }
    }

    private String createGame(Peer white, Peer black) throws Exception {
        white.send("CREATE_ROOM|Trắng\n");
        String room = white.receive().split("\\|", -1)[1];
        black.send("JOIN_ROOM|" + room + "|Đen\n");
        assertTrue(white.receive().startsWith("GAME_START|" + room + "|WHITE|"));
        assertTrue(black.receive().startsWith("GAME_START|" + room + "|BLACK|"));
        return room;
    }

    @Test
    void fragmentedAndCombinedLinesKeepTheirOrder() throws Exception {
        try (GameServer server = startServer(5000, 4); Peer peer = new Peer(server)) {
            peer.send("PI");
            peer.send("NG\r\nwrong-command\nPING\n");
            assertEquals("PONG", peer.receive());
            assertEquals("ERROR|BAD_MESSAGE", peer.receive());
            assertEquals("PONG", peer.receive());
            // Gửi nhiều phản hồi liên tiếp để kiểm tra thứ tự hàng đợi.
            peer.send("PING\n".repeat(100));
            for (int i = 0; i < 100; i++) { assertEquals("PONG", peer.receive()); }
        }
    }

    @Test
    void utf8ByteLimitAndCrlfAreHandledBeforeDecoding() throws Exception {
        try (GameServer server = startServer(5000, 4); Peer peer = new Peer(server)) {
            peer.send("X".repeat(ProtocolCodec.MAX_LINE_BYTES) + "\r\nPING\n");
            assertEquals("ERROR|BAD_MESSAGE", peer.receive());
            assertEquals("PONG", peer.receive());
            peer.send("á".repeat(ProtocolCodec.MAX_LINE_BYTES / 2 + 1) + "\n");
            assertNull(peer.receive());
            try (Peer healthy = new Peer(server)) {
                healthy.send("PING\n");
                assertEquals("PONG", healthy.receive());
            }
        }
    }

    @Test
    void malformedUtf8ClosesOnlyThatConnection() throws Exception {
        try (GameServer server = startServer(5000, 4); Peer peer = new Peer(server)) {
            peer.socket.getOutputStream().write(new byte[] {(byte) 0xc3, 0x28, '\n'});
            peer.socket.getOutputStream().flush();
            assertNull(peer.receive());
            try (Peer healthy = new Peer(server)) {
                healthy.send("PING\n");
                assertEquals("PONG", healthy.receive());
            }
        }
    }

    @Test
    void incompleteCommandIsNotExecutedOnEof() throws Exception {
        try (GameServer server = startServer(5000, 4); Peer peer = new Peer(server)) {
            peer.send("PING");
            peer.socket.shutdownOutput();
            assertNull(peer.receive());
        }
    }

    @Test
    void timeoutClosesIdleAndPartialLines() throws Exception {
        try (GameServer server = startServer(250, 4);
             Peer idle = new Peer(server); Peer partial = new Peer(server)) {
            partial.send("PI");
            assertNull(idle.receive());
            assertNull(partial.receive());
        }
    }

    @Test
    void connectionLimitAndShutdownReleaseSocketsAndPort() throws Exception {
        GameServer server = startServer(5000, 1);
        int port = server.getPort();
        try (server; Peer first = new Peer(server)) {
            first.send("PING\n");
            assertEquals("PONG", first.receive());
            try (Peer rejected = new Peer(server)) { assertNull(rejected.receive()); }
            server.close();
            assertEquals(0, server.getConnectionCount());
            assertNull(first.receive());
        }
        try (java.net.ServerSocket reused = new java.net.ServerSocket()) {
            reused.setReuseAddress(true);
            reused.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;

        Peer(GameServer server) throws IOException {
            socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(3000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        String receive() throws IOException { return reader.readLine(); }
        @Override public void close() throws IOException { socket.close(); }
    }
}
