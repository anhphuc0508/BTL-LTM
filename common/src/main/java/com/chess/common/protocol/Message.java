package com.chess.common.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Shared wire envelope; the payload is copied to prevent accidental mutation after routing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(MessageType type, String requestId, String roomId, ObjectNode payload) {
    public Message {
        Objects.requireNonNull(type, "type");
        if (requestId != null && !requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid requestId");
        }
        if (roomId != null && !roomId.matches("[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid roomId");
        }
        payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
    }

    @Override
    public ObjectNode payload() {
        return payload.deepCopy();
    }
}
