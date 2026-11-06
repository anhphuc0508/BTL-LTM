package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.chess.common.protocol.MessageType;
import com.chess.common.protocol.ProtocolCodec;
import com.chess.common.protocol.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import static com.chess.common.protocol.MessageType.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only TCP harness. Production accept loop, bounded framing and session lifecycle belong to member 1. */
@Timeout(20)
class GameServiceTcpIntegrationTest {
    private static final ProtocolCodec CODEC = new ProtocolCodec();

    @Test
    void twoTcpClientsPlayRejectMoveAndHandleEof() throws Exception {
        try (Harness server = new Harness(); Peer white = server.connect(); Peer black = server.connect()) {
            white.send(new Message(CREATE_ROOM, "create", null, CODEC.newPayload().put("playerName", "Trắng")));
            String room = white.receive().roomId();
            black.send(new Message(JOIN_ROOM, "join", room, CODEC.newPayload().put("playerName", "Đen")));
            assertEquals(GAME_START, white.receive().type());
            assertEquals("BLACK", black.receive().payload().path("yourColor").asText());

            white.send(new Message(MOVE, "move", room, CODEC.newPayload().put("move", "e2e4")));
            Message a = white.receive();
            Message b = black.receive();
            assertEquals(STATE, a.type());
            assertEquals(a.payload().path("fen"), b.payload().path("fen"));
            white.send(new Message(MOVE, "again", room, CODEC.newPayload().put("move", "d2d4")));
            assertEquals(MOVE_REJECTED, white.receive().type());

            black.close();
            assertEquals(OPPONENT_DISCONNECTED, white.receive().type());
            Message end = white.receive();
            assertEquals(GAME_END, end.type());
            assertEquals("WHITE_WIN", end.payload().path("result").asText());
            assertEquals(1, end.payload().path("history").size());
        }
    }

    @Test
    void concatenatedLinesAndMalformedJsonDoNotBreakFollowingRequests() throws Exception {
        try (Harness server = new Harness(); Peer peer = server.connect()) {
            peer.raw(CODEC.encode(new Message(PING, "p1", null, null))
                    + "{broken json}\n" + CODEC.encode(new Message(PING, "p2", null, null)));
            assertEquals("p1", peer.receive().requestId());
            Message error = peer.receive();
            assertEquals(ERROR, error.type());
            assertEquals("BAD_MESSAGE", error.payload().path("reason").asText());
            assertEquals("p2", peer.receive().requestId());
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

        void send(Message message) throws IOException {
            raw(CODEC.encode(message));
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
                } catch (Exception ex) {
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
                    try {
                        route(game.handle(session, CODEC.decode(line)));
                    } catch (ProtocolException ex) {
                        peer.send(new Message(ERROR, null, null, CODEC.newPayload().put("reason", ex.code())));
                    }
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
                Peer recipient = connections.get(message.recipientSessionId());
                if (recipient != null) {
                    try {
                        recipient.send(message.message());
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
