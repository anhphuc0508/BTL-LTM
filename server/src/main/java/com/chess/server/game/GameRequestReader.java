package com.chess.server.game;

import com.chess.common.protocol.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;

/** Đọc dữ liệu JSON đầu vào. GameService nhận lại các giá trị Java đơn giản. */
final class GameRequestReader {
    String readCreatorName(Message request) {
        checkFields(request.getPayload(), "playerName");
        if (request.getRoomId() != null) {
            throw new GameException("INVALID_PAYLOAD");
        }
        return readPlayerName(request);
    }

    String readPlayerName(Message request) {
        ObjectNode payload = request.getPayload();
        checkFields(payload, "playerName");
        String rawName = requiredText(payload, "playerName");
        String name = rawName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 40) {
            throw new GameException("INVALID_PLAYER_NAME");
        }
        for (int i = 0; i < rawName.length(); i++) {
            if (Character.isISOControl(rawName.charAt(i))) {
                throw new GameException("INVALID_PLAYER_NAME");
            }
        }
        return name;
    }

    String readMove(Message request) {
        ObjectNode payload = request.getPayload();
        checkFields(payload, "move");
        String move = requiredText(payload, "move");
        // Ví dụ e2e4: ô đi + ô đến. Ký tự q/r/b/n cuối là quân được phong cấp, nếu có.
        if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new GameException("INVALID_MOVE_FORMAT");
        }
        return move;
    }

    String readRoomId(Message request) {
        String roomId = request.getRoomId();
        if (roomId == null) {
            throw new GameException("ROOM_ID_REQUIRED");
        }
        return roomId;
    }

    int readHistoryStart(Message request) {
        ObjectNode payload = request.getPayload();
        checkFields(payload, "fromPly");
        JsonNode value = payload.get("fromPly");
        if (value == null) {
            return 1;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new GameException("INVALID_PAYLOAD");
        }
        return value.intValue();
    }

    void requireEmptyPayload(Message request) {
        checkFields(request.getPayload());
    }

    void validateSessionId(String sessionId) {
        // Session do tầng TCP gán; chỉ nhận ký tự an toàn để dùng trong log.
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid server-generated sessionId");
        }
    }

    private String requiredText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new GameException("INVALID_PAYLOAD");
        }
        return value.textValue();
    }

    private void checkFields(ObjectNode payload, String... allowedFields) {
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            boolean allowed = false;
            for (String allowedField : allowedFields) {
                if (allowedField.equals(field)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new GameException("INVALID_PAYLOAD");
            }
        }
    }
}
