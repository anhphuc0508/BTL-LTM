package com.chess.client;

import com.chess.client.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.awt.Component;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Protocol v2: UTF-8, one line per message, fields separated by '|'.
 * Matches BTL-LTM/feature/game-protocol/docs/protocol.md (06/11/2026).
 * Uses the existing five UI classes. Start via ConnectionFrame.main().
 * Construct and call start()/synchronizeHistory() on the Swing EDT.
 */
public class ChessClient {
    private static final Logger log = LoggerFactory.getLogger(ChessClient.class);
    private static final int MAX_BYTES = 16_384;
    private static final int RESPONSE_TIMEOUT_MS = 15_000;
    private final ConnectionFrame connectionFrame;
    private LobbyFrame lobby;
    private GameFrame game;
    private TcpConnection connection;
    private int generation;
    private String playerName;
    private Snapshot state;
    private final TreeMap<Integer, String> history = new TreeMap<>();
    private String pending; // Only one application request outstanding; no requestId in v2.
    private int pendingPly;
    private javax.swing.Timer responseTimer;
    private int historyFrom, pageRows;
    private boolean pageStateReceived, opponentGone;
    private String announcedResult;

    public ChessClient() {
        connectionFrame = new ConnectionFrame(this::connect, this::exit);
    }

    public void start() { connectionFrame.setVisible(true); }

    private void connect(String host, int port, String name) {
        try {
            if (host == null || host.isBlank() || port < 1 || port > 65535)
                throw new IllegalArgumentException("IP hoặc cổng không hợp lệ.");
            playerName = validateName(name);
        } catch (IllegalArgumentException e) {
            connectionFrame.showError(e.getMessage());
            return;
        }
        if (connection != null) { generation++; connection.close(); }
        clearPending();
        state = null;
        history.clear();
        opponentGone = false;
        announcedResult = null;
        connectionFrame.setBusy(true);
        connection = new TcpConnection(host.trim(), port, ++generation);
        connection.open();
    }

    private void onConnected() {
        connectionFrame.setBusy(false);
        connectionFrame.setVisible(false);
        lobby = new LobbyFrame(playerName,
                () -> requestRoom(null), this::requestRoom, this::disconnectToLogin);
        lobby.setVisible(true);
    }

    private void requestRoom(String room) {
        if (pending != null) return;
        if (room != null && !room.matches("[A-Z0-9]{6}")) {
            showError("Mã phòng phải gồm đúng 6 chữ cái in hoa hoặc chữ số.");
            return;
        }
        String type = room == null ? "CREATE_ROOM" : "JOIN_ROOM";
        String line = room == null ? "CREATE_ROOM|" + playerName
                : "JOIN_ROOM|" + room + "|" + playerName;
        lobby.setBusy(true);
        if (!request(type, line)) lobby.setBusy(false);
    }

    private boolean request(String type, String line) {
        if (pending != null) return false;
        try {
            if (connection == null || !connection.isConnected())
                throw new IllegalStateException("Chưa kết nối Server.");
            pending = type;
            pendingPly = state == null ? 0 : state.ply;
            connection.sendLine(line);
            armTimeout();
            refreshControls();
            return true;
        } catch (RuntimeException e) {
            clearPending();
            refreshControls();
            showError(e.getMessage());
            return false;
        }
    }

    private void armTimeout() {
        if (responseTimer != null) responseTimer.stop();
        int token = generation;
        responseTimer = new javax.swing.Timer(RESPONSE_TIMEOUT_MS, e -> {
            if (token != generation || pending == null) return;
            String operation = pending;
            log.warn("event=RESPONSE_TIMEOUT command={}", operation);
            disconnectToLogin();
            connectionFrame.showError("Server không hoàn tất phản hồi " + operation
                    + " trong 15 giây. Đã ngắt kết nối để tránh tiếp tục trên trạng thái chưa xác nhận."
                    + "\nBản hiện tại không hỗ trợ quay lại ván cũ sau khi kết nối lại.");
        });
        responseTimer.setRepeats(false);
        responseTimer.start();
    }

    private void clearPending() {
        if (responseTimer != null) responseTimer.stop();
        responseTimer = null;
        pending = null;
    }

    private void handleLine(String line) {
        try {
            String[] p = decode(line);
            switch (p[0]) {
                case "ROOM_CREATED", "GAME_START", "STATE", "GAME_END" -> applySnapshot(p);
                case "HISTORY" -> receiveHistory(p);
                case "HISTORY_END" -> finishHistoryPage(p);
                case "MOVE_REJECTED" -> {
                    fields(p, 3);
                    if (state == null || (!p[1].isEmpty() && !p[1].equals(state.room)))
                        throw new IllegalArgumentException("MOVE_REJECTED sai phòng");
                    if ("MOVE".equals(pending)) {
                        clearPending();
                        refreshControls();
                        game.setStatus("Nước đi bị từ chối: " + p[2]);
                    }
                    log.warn("event=MOVE_REJECTED roomId={} reason={}", p[1], p[2]);
                }
                case "ERROR" -> {
                    fields(p, 2);
                    String failed = pending;
                    clearPending();
                    log.warn("event=SERVER_ERROR reason={}", p[1]);
                    if ("GET_STATE".equals(failed)) {
                        disconnectToLogin();
                        connectionFrame.showError("Không đồng bộ được lịch sử: " + p[1]);
                    } else {
                        if (lobby != null && state == null) lobby.setBusy(false);
                        refreshControls();
                        showError("Server từ chối yêu cầu: " + p[1]);
                    }
                }
                case "OPPONENT_DISCONNECTED" -> {
                    fields(p, 5);
                    requireMatch(p[1], p[2]);
                    if (nonNegativeLong(p[3]) < state.version) return;
                    if (!p[4].equals("DISCONNECT")) throw new IllegalArgumentException("Sai lý do disconnect");
                    opponentGone = true;
                    // Keep a history transfer outstanding until HISTORY_END.
                    if (!"GET_STATE".equals(pending)) clearPending();
                    refreshControls();
                    log.info("event=OPPONENT_DISCONNECTED roomId={}", state.room);
                }
                case "PONG" -> { fields(p, 1); log.debug("event=PONG"); }
                default -> throw new IllegalArgumentException("Lệnh Server không được hỗ trợ: " + p[0]);
            }
        } catch (RuntimeException e) {
            log.error("event=INVALID_RESPONSE", e);
            disconnectToLogin();
            connectionFrame.showError("Phản hồi Server không đúng protocol v2. Xem log để đối chiếu.");
        }
    }

    private void applySnapshot(String[] p) {
        Snapshot incoming = Snapshot.parse(p);
        if (state != null && (!state.room.equals(incoming.room) || !state.match.equals(incoming.match)))
            throw new IllegalArgumentException("Snapshot thuộc phòng hoặc ván khác");
        if (state == null && !"CREATE_ROOM".equals(pending) && !"JOIN_ROOM".equals(pending))
            throw new IllegalArgumentException("Snapshot không được yêu cầu");
        if (p[0].equals("STATE") && "GET_STATE".equals(pending)) pageStateReceived = true;
        if (state != null && incoming.version < state.version) return;
        if (state != null && incoming.version == state.version && !incoming.equals(state))
            throw new IllegalArgumentException("Cùng version nhưng nội dung khác nhau");
        if (state != null && incoming.ply < state.ply)
            throw new IllegalArgumentException("Ply bị lùi");
        if (p[0].equals("ROOM_CREATED") && !incoming.status.equals("WAITING"))
            throw new IllegalArgumentException("ROOM_CREATED phải WAITING");
        if (p[0].equals("GAME_END") && !incoming.status.equals("FINISHED"))
            throw new IllegalArgumentException("GAME_END phải FINISHED");
        if (state != null && state.status.equals("FINISHED") && !incoming.status.equals("FINISHED"))
            throw new IllegalArgumentException("Ván đã kết thúc không thể hoạt động lại");

        boolean changed = state == null || incoming.version > state.version;
        if (!incoming.status.equals("WAITING")) {
            ensureGame();
            // Apply validated authoritative board first, then update model/history.
            if (changed) game.getBoardPanel().setFen(incoming.fen);
        }
        state = incoming;
        if (state.ply > 0) rememberMove(state.ply, state.lastMove);

        if (p[0].equals("ROOM_CREATED") && "CREATE_ROOM".equals(pending)
                || p[0].equals("GAME_START") && ("JOIN_ROOM".equals(pending) || "CREATE_ROOM".equals(pending))
                || p[0].equals("STATE") && "MOVE".equals(pending) && state.ply > pendingPly
                || state.status.equals("FINISHED") && pending != null && !pending.equals("GET_STATE")) {
            clearPending();
        }
        if (state.status.equals("WAITING")) {
            if (lobby == null) throw new IllegalArgumentException("Thiếu phòng chờ");
            lobby.showRoom(state.room);
        } else {
            game.getBoardPanel().setFlipped(state.color.equals("BLACK"));
            game.setPlayerInfo(playerName, state.color.equals("WHITE") ? "Trắng" : "Đen", state.room);
            if (state.ply > 0) game.getBoardPanel().setLastMove(
                    square(state.lastMove.substring(0, 2)), square(state.lastMove.substring(2, 4)));
            if (lobby != null) { lobby.dispose(); lobby = null; }
            game.setVisible(true);
            refreshControls();
            // Auto-fetch missing history; never issue parallel GET_STATE requests.
            if (pending == null && firstMissing() <= state.ply) requestHistory(firstMissing());
            if (state.status.equals("FINISHED") && !state.match.equals(announcedResult)) {
                announcedResult = state.match;
                // Non-modal status prevents nested event loops while receiving history.
                log.info("event=GAME_END roomId={} result={} reason={}", state.room, state.result, state.reason);
            }
        }
        log.info("event=RECEIVE_STATE roomId={} version={} ply={} status={}",
                state.room, state.version, state.ply, state.status);
    }

    private void ensureGame() {
        if (game != null) return;
        game = new GameFrame(this::resign, this::disconnectToLogin);
        game.getBoardPanel().setMoveSelectionListener(this::selectMove);
        game.getHistoryPanel().clearHistory();
    }

    private void rememberMove(int ply, String move) {
        String old = history.putIfAbsent(ply, move);
        if (old != null && !old.equals(move)) throw new IllegalArgumentException("Lịch sử mâu thuẫn tại ply=" + ply);
        if (game != null) game.getHistoryPanel().addConfirmedMove(ply, move);
    }

    private int firstMissing() {
        int next = 1;
        for (int ply : history.keySet()) { if (ply != next) break; next++; }
        return next;
    }

    /** Optional refresh entry point, call on EDT. Not a reconnect/resume mechanism. */
    public void synchronizeHistory() {
        if (state != null && game != null && pending == null) requestHistory(1);
    }

    private void requestHistory(int from) {
        historyFrom = from;
        pageRows = 0;
        pageStateReceived = false;
        request("GET_STATE", "GET_STATE|" + state.room + "|" + from);
    }

    private void receiveHistory(String[] p) {
        fields(p, 8);
        requireMatch(p[1], p[2]);
        if (!"GET_STATE".equals(pending) || !pageStateReceived)
            throw new IllegalArgumentException("HISTORY ngoài đợt đồng bộ");
        int ply = nonNegativeInt(p[3]);
        if (ply != historyFrom + pageRows || ply > state.ply || ++pageRows > 100)
            throw new IllegalArgumentException("Sai thứ tự trang lịch sử");
        if (!p[4].equals(ply % 2 == 1 ? "WHITE" : "BLACK"))
            throw new IllegalArgumentException("Sai màu trong lịch sử");
        validateMove(p[5]);
        Instant.parse(p[6]);
        validateFen(p[7]);
        rememberMove(ply, p[5]);
        armTimeout();
    }

    private void finishHistoryPage(String[] p) {
        fields(p, 4);
        requireMatch(p[1], p[2]);
        if (!"GET_STATE".equals(pending) || !pageStateReceived)
            throw new IllegalArgumentException("HISTORY_END không được yêu cầu");
        int next = nonNegativeInt(p[3]);
        if (next != 0 && (pageRows != 100 || next != historyFrom + pageRows))
            throw new IllegalArgumentException("nextPly không hợp lệ");
        clearPending();
        if (next > 0) requestHistory(next);
        else if (firstMissing() <= state.ply)
            throw new IllegalArgumentException("Lịch sử chưa đầy đủ sau HISTORY_END");
        else { log.info("event=HISTORY_SYNCED roomId={} count={}", state.room, history.size()); refreshControls(); }
    }

    private void selectMove(ChessBoardPanel.Square from, ChessBoardPanel.Square to) {
        if (!canMove()) return;
        char piece = pieceAt(state.fen, from.row(), from.column());
        if (piece == 0 || Character.isUpperCase(piece) != state.color.equals("WHITE")) {
            game.setStatus("Bạn chỉ được đi quân của mình"); return;
        }
        String move = squareName(from) + squareName(to);
        if (piece == 'P' && to.row() == 0 || piece == 'p' && to.row() == 7) {
            Snapshot before = state;
            int token = generation;
            String[] choices = {"Hậu", "Xe", "Tượng", "Mã"};
            int choice = JOptionPane.showOptionDialog(game, "Chọn quân phong cấp", "Phong cấp",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
            if (choice < 0 || token != generation || !before.equals(state) || !canMove()) return;
            move += "qrbn".charAt(choice);
        }
        if (request("MOVE", "MOVE|" + state.room + "|" + move))
            log.info("event=SEND_MOVE roomId={} move={}", state.room, move);
    }

    private void resign() {
        if (state != null && state.status.equals("IN_PROGRESS") && !opponentGone && pending == null)
            request("RESIGN", "RESIGN|" + state.room);
    }

    private boolean canMove() {
        return state != null && game != null && pending == null && !opponentGone
                && state.status.equals("IN_PROGRESS") && state.color.equals(state.turn)
                && firstMissing() > state.ply;
    }

    private void refreshControls() {
        if (game == null || state == null) return;
        game.setControls(canMove(), pending == null && !opponentGone && state.status.equals("IN_PROGRESS"));
        if (state.status.equals("FINISHED")) {
            String result = switch (state.result) {
                case "WHITE_WIN" -> "Trắng thắng";
                case "BLACK_WIN" -> "Đen thắng";
                default -> "Hòa";
            };
            game.setStatus(result + " • " + state.reason);
        } else if (opponentGone) game.setStatus("Đối thủ mất kết nối • Chờ kết quả");
        else if (pending != null) game.setStatus(pending.equals("GET_STATE")
                ? "Đang đồng bộ lịch sử..." : "Đang chờ Server xác nhận...");
        else game.setStatus((state.color.equals(state.turn) ? "Đến lượt bạn" : "Đang chờ đối thủ")
                + (state.inCheck ? " • Chiếu" : ""));
    }

    private void requireMatch(String room, String match) {
        if (state == null || !state.room.equals(room) || !state.match.equals(match))
            throw new IllegalArgumentException("Sai phòng/matchId");
    }

    private void disconnectToLogin() {
        disposeSession();
        connectionFrame.setBusy(false);
        connectionFrame.setVisible(true);
    }

    private void disposeSession() {
        generation++;
        clearPending();
        if (connection != null) { connection.close(); connection = null; }
        if (lobby != null) { lobby.dispose(); lobby = null; }
        if (game != null) { game.dispose(); game = null; }
        state = null;
        history.clear();
        opponentGone = false;
        announcedResult = null;
    }

    private void exit() { disposeSession(); connectionFrame.dispose(); }

    private void showError(String message) {
        Component parent = game != null ? game : lobby != null ? lobby : connectionFrame;
        JOptionPane.showMessageDialog(parent, message, "Thông báo", JOptionPane.WARNING_MESSAGE);
    }

    // ---------------- Protocol validation: no JSON/Gson ----------------
    private static String[] decode(String line) {
        if (line == null || line.isEmpty() || line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || line.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Dòng rỗng/quá dài/chứa ký tự điều khiển");
        return line.split("\\|", -1); // DO NOT trim: preserve empty fields.
    }
    private static void fields(String[] p, int count) {
        if (p.length != count) throw new IllegalArgumentException("Sai số trường của " + p[0]);
    }
    private static String validateName(String name) {
        if (name == null) throw new IllegalArgumentException("Thiếu tên người chơi");
        String s = name.strip();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > 40 || s.contains("|")
                || s.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Tên dài 1–40 ký tự, không chứa | hoặc ký tự điều khiển.");
        return s;
    }
    private static long nonNegativeLong(String s) {
        if (!s.matches("[0-9]+")) throw new IllegalArgumentException("Số không hợp lệ");
        return Long.parseLong(s);
    }
    private static int nonNegativeInt(String s) { return Math.toIntExact(nonNegativeLong(s)); }
    private static void color(String s) {
        if (!s.equals("WHITE") && !s.equals("BLACK")) throw new IllegalArgumentException("Màu không hợp lệ");
    }
    private static void validateMove(String s) {
        if (!s.matches("[a-h][1-8][a-h][1-8][qrbn]?")) throw new IllegalArgumentException("UCI không hợp lệ");
    }
    private static void validateFen(String fen) {
        String[] f = fen.split(" ", -1);
        if (f.length != 6 || !f[1].matches("[wb]") || !f[2].matches("-|K?Q?k?q?") || f[2].isEmpty()
                || !f[3].matches("-|[a-h][36]") || !f[4].matches("[0-9]+") || !f[5].matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("FEN không hợp lệ");
        String[] ranks = f[0].split("/", -1);
        if (ranks.length != 8) throw new IllegalArgumentException("FEN cần 8 hàng");
        for (String rank : ranks) {
            int total = 0; boolean digit = false;
            for (char c : rank.toCharArray()) {
                if (c >= '1' && c <= '8') {
                    if (digit) throw new IllegalArgumentException("FEN có số ô trống liền nhau");
                    total += c - '0'; digit = true;
                } else {
                    if ("prnbqkPRNBQK".indexOf(c) < 0) throw new IllegalArgumentException("Quân FEN không hợp lệ");
                    total++; digit = false;
                }
            }
            if (total != 8) throw new IllegalArgumentException("Hàng FEN phải có 8 ô");
        }
    }
    private static String squareName(ChessBoardPanel.Square s) {
        return "" + (char) ('a' + s.column()) + (8 - s.row());
    }
    private static ChessBoardPanel.Square square(String s) {
        return new ChessBoardPanel.Square(8 - (s.charAt(1) - '0'), s.charAt(0) - 'a');
    }
    private static char pieceAt(String fen, int row, int col) {
        int x = 0;
        for (char c : fen.split(" ")[0].split("/")[row].toCharArray()) {
            if (c >= '1' && c <= '8') x += c - '0';
            else { if (x == col) return c; x++; }
        }
        return 0;
    }

    private record Snapshot(String room, String color, String status, String turn, int ply,
            String fen, boolean inCheck, String result, String reason, String match, long version,
            String lastMove, String whiteName, String blackName) {
        static Snapshot parse(String[] p) {
            fields(p, 15);
            if (!p[1].matches("[A-Z0-9]{6}")) throw new IllegalArgumentException("Sai mã phòng");
            ChessClient.color(p[2]); ChessClient.color(p[4]);
            if (!Set.of("WAITING", "IN_PROGRESS", "FINISHED").contains(p[3]))
                throw new IllegalArgumentException("Sai status");
            int ply = nonNegativeInt(p[5]);
            validateFen(p[6]);
            if (!p[4].equals(p[6].split(" ")[1].equals("w") ? "WHITE" : "BLACK"))
                throw new IllegalArgumentException("turn khác FEN");
            if (!p[7].equals("true") && !p[7].equals("false")) throw new IllegalArgumentException("Sai inCheck");
            UUID.fromString(p[10]);
            long version = nonNegativeLong(p[11]);
            if (ply > 0) validateMove(p[12]);
            else if (!p[12].isEmpty()) throw new IllegalArgumentException("ply=0 nhưng có lastMove");
            validateName(p[13]);
            if (!p[14].isEmpty()) validateName(p[14]);
            if (p[3].equals("FINISHED")) {
                if (!Set.of("WHITE_WIN", "BLACK_WIN", "DRAW").contains(p[8]) || p[9].isEmpty())
                    throw new IllegalArgumentException("Thiếu kết quả kết thúc");
            } else if (!p[8].isEmpty() || !p[9].isEmpty()) throw new IllegalArgumentException("Kết quả xuất hiện trước kết thúc");
            return new Snapshot(p[1], p[2], p[3], p[4], ply, p[6], Boolean.parseBoolean(p[7]),
                    p[8], p[9], p[10], version, p[12], p[13], p[14]);
        }
    }

    // ---------------- TCP transport (nested, no extra .java file) ----------------
    private final class TcpConnection implements AutoCloseable {
        private final String host;
        private final int port, token;
        private final Socket socket = new Socket();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean connected;
        private InputStream input;
        private OutputStream output;
        private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> {
                    Thread t = new Thread(task, "chess-tcp-sender"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        // Prevent an unlimited backlog of Swing events from a noisy peer.
        private final Semaphore deliveries = new Semaphore(256);
        private TcpConnection(String host, int port, int token) { this.host = host; this.port = port; this.token = token; }
        private boolean isConnected() { return connected && !closed.get(); }
        private void open() {
            sender.execute(() -> {
                try {
                    if (closed.get()) return;
                    socket.connect(new InetSocketAddress(host, port), 5000);
                    socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                    input = new BufferedInputStream(socket.getInputStream());
                    output = new BufferedOutputStream(socket.getOutputStream());
                    if (closed.get()) return;
                    connected = true;
                    log.info("event=CLIENT_CONNECTED");
                    SwingUtilities.invokeLater(() -> { if (token == generation && !closed.get()) onConnected(); });
                    Thread receiver = new Thread(this::receiveLoop, "chess-tcp-receiver");
                    receiver.setDaemon(true); receiver.start();
                } catch (IOException | RuntimeException e) { stop("CONNECT_FAILED", e); }
            });
        }
        private void sendLine(String line) {
            decode(line);
            if (!isConnected()) throw new IllegalStateException("Kết nối đã đóng");
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            sender.execute(() -> {
                if (!isConnected()) return;
                try { output.write(bytes); output.write('\n'); output.flush(); }
                catch (IOException e) { stop("SEND_FAILED", e); }
            });
        }
        private void receiveLoop() {
            try {
                while (!closed.get()) {
                    String line = readLine();
                    if (line == null) { stop("SERVER_DISCONNECTED", null); return; }
                    while (!closed.get() && !deliveries.tryAcquire(200, TimeUnit.MILLISECONDS)) { }
                    if (closed.get()) return;
                    SwingUtilities.invokeLater(() -> {
                        try { if (token == generation) handleLine(line); }
                        finally { deliveries.release(); }
                    });
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); stop("RECEIVE_INTERRUPTED", e); }
            catch (IOException e) { stop("RECEIVE_FAILED", e); }
        }
        private String readLine() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int b = input.read();
                if (b == -1) {
                    if (bytes.size() == 0) return null;
                    throw new EOFException("Kết nối đóng giữa thông điệp");
                }
                if (b == '\n') {
                    byte[] data = bytes.toByteArray();
                    int length = data.length;
                    if (length > 0 && data[length - 1] == '\r') length--;
                    if (length > MAX_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data, 0, length)).toString();
                }
                // Allow a single trailing CR beyond MAX_BYTES, checked at LF.
                if (bytes.size() >= MAX_BYTES && !(bytes.size() == MAX_BYTES && b == '\r'))
                    throw new IOException("MESSAGE_TOO_LARGE");
                bytes.write(b);
            }
        }
        private void stop(String reason, Throwable error) {
            if (!closed.compareAndSet(false, true)) return;
            connected = false;
            try { socket.close(); } catch (IOException e) { log.debug("event=CLOSE_ERROR", e); }
            sender.shutdownNow();
            if (error == null) log.info("event={}", reason);
            else log.warn("event={}", reason, error);
            SwingUtilities.invokeLater(() -> {
                if (token != generation) return;
                disconnectToLogin();
                connectionFrame.showError("Kết nối kết thúc: " + reason + "\nKiểm tra Server, IP và cổng.");
            });
        }
        @Override public void close() { stop("CLIENT_CLOSED", null); }
    }
}
