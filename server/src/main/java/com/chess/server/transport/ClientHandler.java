package com.chess.server.transport;

import com.chess.common.protocol.ProtocolCodec;
import com.chess.common.protocol.ProtocolException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Đọc từng dòng có giới hạn byte, rồi chuyển nguyên chuỗi cho GameService. */
public final class ClientHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final SessionContext session;
    private final ConnectionManager connections;
    private final int timeoutMillis;

    public ClientHandler(SessionContext session, ConnectionManager connections, int timeoutMillis) {
        this.session = session;
        this.connections = connections;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void run() {
        String reason = "EOF";
        try {
            BufferedInputStream input = new BufferedInputStream(session.getSocket().getInputStream());
            while (!session.getSocket().isClosed()) {
                String line = readLine(input);
                if (line == null) { break; }
                connections.handle(session.getId(), line);
            }
        } catch (SocketTimeoutException ex) {
            reason = "TIMEOUT";
            log.warn("event=TIMEOUT sessionId={}", session.getId());
        } catch (CharacterCodingException ex) {
            reason = "INVALID_UTF8";
            log.warn("event=BAD_MESSAGE sessionId={} reason={}", session.getId(), reason);
        } catch (ProtocolException ex) {
            reason = ex.getCode();
            log.warn("event=BAD_MESSAGE sessionId={} reason={}", session.getId(), reason);
        } catch (IOException ex) {
            reason = "IO_ERROR";
            if (!session.getSocket().isClosed()) {
                log.error("event=IO_ERROR sessionId={}", session.getId(), ex);
            }
        } catch (RuntimeException ex) {
            reason = "UNHANDLED_EXCEPTION";
            log.error("event=UNHANDLED_EXCEPTION sessionId={}", session.getId(), ex);
        } finally {
            connections.disconnect(session.getId(), reason);
        }
    }

    private String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { throw new SocketTimeoutException(); }
            session.getSocket().setSoTimeout((int) Math.max(1, remaining / 1_000_000));
            int value = input.read();
            if (value == -1) {
                // Không xử lý lệnh bị cắt giữa dòng khi Client đóng kết nối.
                if (bytes.size() > 0) {
                    log.warn("event=BAD_MESSAGE sessionId={} reason=INCOMPLETE_LINE", session.getId());
                }
                return null;
            }
            if (value == '\n') { break; }
            // Cho phép thêm một byte CR khi bên gửi dùng CRLF.
            if (bytes.size() >= ProtocolCodec.MAX_LINE_BYTES + 1) {
                throw new ProtocolException("MESSAGE_TOO_LARGE");
            }
            bytes.write(value);
        }
        byte[] data = bytes.toByteArray();
        int length = data.length;
        if (length > 0 && data[length - 1] == '\r') { length--; }
        if (length > ProtocolCodec.MAX_LINE_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, 0, length)).toString();
    }
}
