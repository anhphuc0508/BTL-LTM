package com.chess.server.game;

import com.chess.common.protocol.Message;

/** Đọc các ô chuỗi đã được tách bằng dấu |. */
final class GameRequestReader {
    String readCreatorName(Message request) {
        requireFieldCount(request, 1);
        return readName(request.getField(0));
    }

    String readPlayerName(Message request) {
        requireFieldCount(request, 2);
        return readName(request.getField(1));
    }

    private String readName(String rawName) {
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
        requireFieldCount(request, 2);
        String move = request.getField(1);
        // e2e4 = ô đi + ô đến; q/r/b/n ở cuối nếu phong cấp.
        if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new GameException("INVALID_MOVE_FORMAT");
        }
        return move;
    }

    String readRoomId(Message request) {
        if (request.getFieldCount() == 0 || request.getField(0).isEmpty()) {
            throw new GameException("ROOM_ID_REQUIRED");
        }
        String roomId = request.getField(0);
        if (!roomId.matches("[A-Z0-9]{6}")) {
            throw new GameException("INVALID_ROOM_ID");
        }
        return roomId;
    }

    int readHistoryStart(Message request) {
        if (request.getFieldCount() == 1) {
            return 1;
        }
        requireFieldCount(request, 2);
        try {
            String text = request.getField(1);
            if (!text.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            int fromPly = Integer.parseInt(text);
            if (fromPly < 1) {
                throw new NumberFormatException();
            }
            return fromPly;
        } catch (NumberFormatException ex) {
            throw new GameException("INVALID_ARGUMENTS");
        }
    }

    void requireFieldCount(Message request, int count) {
        if (request.getFieldCount() != count) {
            throw new GameException("INVALID_ARGUMENTS");
        }
    }

    void validateSessionId(String sessionId) {
        // Session do Server gán, không nhận từ chuỗi Client gửi.
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid server-generated sessionId");
        }
    }
}
