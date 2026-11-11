package com.chess.server.game;

import java.util.Objects;

/** Gửi chuỗi nào, cho kết nối nào. Chuỗi chưa có ký tự xuống dòng. */
public final class OutboundMessage {
    private final String recipientSessionId;
    private final String message;

    public OutboundMessage(String recipientSessionId, String message) {
        this.recipientSessionId = Objects.requireNonNull(recipientSessionId);
        this.message = Objects.requireNonNull(message);
    }

    public String getRecipientSessionId() { return recipientSessionId; }
    public String getMessage() { return message; }
}
