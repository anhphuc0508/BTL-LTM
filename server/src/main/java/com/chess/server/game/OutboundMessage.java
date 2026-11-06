package com.chess.server.game;

import com.chess.common.protocol.Message;

import java.util.Objects;

/** Một phản hồi cần gửi: gửi cho session nào, và gửi thông điệp gì. */
public final class OutboundMessage {
    private final String recipientSessionId;
    private final Message message;

    public OutboundMessage(String recipientSessionId, Message message) {
        this.recipientSessionId = Objects.requireNonNull(recipientSessionId, "recipientSessionId");
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getRecipientSessionId() {
        return recipientSessionId;
    }

    public Message getMessage() {
        return message;
    }

    // Tương thích với code đã dùng API ban đầu.
    public String recipientSessionId() {
        return getRecipientSessionId();
    }

    public Message message() {
        return getMessage();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboundMessage)) {
            return false;
        }
        OutboundMessage output = (OutboundMessage) other;
        return recipientSessionId.equals(output.recipientSessionId) && message.equals(output.message);
    }

    @Override
    public int hashCode() {
        return 31 * recipientSessionId.hashCode() + message.hashCode();
    }

    @Override
    public String toString() {
        return "OutboundMessage[recipientSessionId=" + recipientSessionId + ", message=" + message + "]";
    }
}
