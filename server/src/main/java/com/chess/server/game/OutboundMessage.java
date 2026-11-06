package com.chess.server.game;

import com.chess.common.protocol.Message;

import java.util.Objects;

/** The transport layer resolves recipientSessionId to a connection and writes message through ProtocolCodec. */
public record OutboundMessage(String recipientSessionId, Message message) {
    public OutboundMessage {
        Objects.requireNonNull(recipientSessionId, "recipientSessionId");
        Objects.requireNonNull(message, "message");
    }
}
