package com.chess.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Tách/nối chuỗi thuần túy. Ví dụ: MOVE|ABC234|e2e4. */
public final class ProtocolCodec {
    public static final int MAX_LINE_BYTES = 16_384;

    public Message decode(String line) {
        checkLine(line);
        // -1 giữ lại cả ô rỗng ở cuối dòng.
        String[] parts = line.split("\\|", -1);
        try {
            MessageType type = MessageType.valueOf(parts[0]);
            String[] fields = Arrays.copyOfRange(parts, 1, parts.length);
            return new Message(type, fields);
        } catch (IllegalArgumentException ex) {
            throw new ProtocolException("BAD_MESSAGE");
        }
    }

    /** Trả về chuỗi chưa có xuống dòng. Bên gửi dùng println() và flush(). */
    public String encode(Message message) {
        StringBuilder line = new StringBuilder(message.getType().name());
        for (String field : message.getFields()) {
            if (field == null || field.indexOf('|') >= 0
                    || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                throw new ProtocolException("BAD_MESSAGE");
            }
            line.append('|').append(field);
        }
        String result = line.toString();
        checkLine(result);
        return result;
    }

    private void checkLine(String line) {
        if (line == null || line.isBlank() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ProtocolException("BAD_MESSAGE");
        }
        if (line.length() > MAX_LINE_BYTES || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
    }
}
