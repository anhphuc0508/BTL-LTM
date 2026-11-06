package com.chess.server.game;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.chess.common.protocol.Message;
import static com.chess.common.protocol.MessageType.ERROR;
import static com.chess.common.protocol.MessageType.GAME_END;
import static com.chess.common.protocol.MessageType.GAME_START;
import static com.chess.common.protocol.MessageType.MOVE_REJECTED;
import static com.chess.common.protocol.MessageType.OPPONENT_DISCONNECTED;
import static com.chess.common.protocol.MessageType.PONG;
import static com.chess.common.protocol.MessageType.STATE;
import com.chess.common.protocol.ProtocolCodec;

/** Test-only TCP harness. Production accept loop, bounded framing and session lifecycle belong to member 1. */
@Timeout(20)
class GameServiceTcpIntegrationTest {
    private static final ProtocolCodec CODEC = new ProtocolCodec();

    @Test
    void twoTcpClientsPlayRejectMoveAndHandleEof() throws Exception {
        try (Harness server = new Harness(); Peer white = server.connect(); Peer black = server.connect()) {
            white.send("CREATE_ROOM|Trắng");
            String room = white.receive().getField(0);
            black.send("JOIN_ROOM|" + room + "|Đen");
            Message start = white.receive();
            assertEquals(GAME_START, start.getType());
            assertEquals("Trắng", start.getField(12));
            assertEquals("Đen", start.getField(13));
            assertEquals("BLACK", black.receive().getField(1));

            white.send("MOVE|" + room + "|e2e4");
            Message a = white.receive();
            Message b = black.receive();
            assertEquals(STATE, a.getType());
            assertEquals(a.getField(5), b.getField(5));
            assertEquals("1", a.getField(4));
            white.send("MOVE|" + room + "|d2d4");
            assertEquals(MOVE_REJECTED, white.receive().getType());

            black.close();
            assertEquals(OPPONENT_DISCONNECTED, white.receive().getType());
            Message end = white.receive();
            assertEquals(GAME_END, end.getType());
            assertEquals("WHITE_WIN", end.getField(7));
            assertEquals("1", end.getField(4));
        }
    }

    @Test
    void concatenatedLinesAndMalformedCommandsDoNotBreakFollowingRequests() throws Exception {
        try (Harness server = new Harness(); Peer peer = server.connect()) {
            peer.raw("PING\nnot-a-command\nPING\n");
            assertEquals(PONG, peer.receive().getType());
            Message error = peer.receive();
            assertEquals(ERROR, error.getType());
            assertEquals("BAD_MESSAGE", error.getField(0));
            assertEquals(PONG, peer.receive().getType());
        }
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void raw(String lines) throws IOException {
            writer.write(lines);
            writer.flush();
        }

        void send(String line) throws IOException {
            raw(line + "\n");
        }

        Message receive() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Unexpected EOF in test client");
            }
            return CODEC.decode(line);
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    private static final class Harness implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        final ExecutorService threads = Executors.newCachedThreadPool();
        final ConcurrentHashMap<String, Peer> connections = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        final AtomicInteger ids = new AtomicInteger();
        final GameService game = new GameService(new RoomManager(), MatchEventWriter.disabled(), Clock.systemUTC());

        Harness() throws IOException {
            threads.submit(() -> {
                try {
                    while (!listener.isClosed()) {
                        Socket socket = listener.accept();
                        threads.submit(() -> serve(socket));
                    }
                } catch (SocketException ex) {
                    if (!listener.isClosed()) { errors.add(ex); }
                } catch (IOException ex) {
                    errors.add(ex);
                }
            });
        }

        Peer connect() throws IOException {
            return new Peer(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
        }

        void serve(Socket socket) {
            String session = "tcp-" + ids.incrementAndGet();
            try (Peer peer = new Peer(socket)) {
                connections.put(session, peer);
                String line;
                while ((line = peer.reader.readLine()) != null) {
                    route(game.handle(session, line));
                }
            } catch (SocketException ex) {
                if (!listener.isClosed()) { errors.add(ex); }
            } catch (Exception ex) {
                errors.add(ex);
            } finally {
                connections.remove(session);
                route(game.onDisconnect(session));
            }
        }

        void route(List<OutboundMessage> output) {
            for (OutboundMessage message : output) {
                Peer recipient = connections.get(message.getRecipientSessionId());
                if (recipient != null) {
                    try {
                        recipient.send(message.getMessage());
                    } catch (IOException ex) {
                        if (!listener.isClosed() && !recipient.socket.isClosed()) { errors.add(ex); }
                    }
                }
            }
        }

        @Override public void close() throws Exception {
            listener.close();
            for (Peer peer : connections.values()) { peer.close(); }
            threads.shutdown();
            if (!threads.awaitTermination(5, TimeUnit.SECONDS)) {
                threads.shutdownNow();
                fail("Test transport did not stop");
            }
            assertTrue(errors.isEmpty(), () -> "Test transport errors: " + errors);
        }
    }
}
