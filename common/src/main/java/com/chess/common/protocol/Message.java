package com.chess.common.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Một thông điệp gửi giữa Client và Server: loại lệnh, mã yêu cầu, mã phòng, dữ liệu. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "requestId", "roomId", "payload"})
public final class Message {
    private final MessageType type;
    private final String requestId;
    private final String roomId;
    private final ObjectNode payload;

    // Các annotation cho Jackson biết tên trường JSON tương ứng với tham số constructor.
    @JsonCreator
    public Message(@JsonProperty("type") MessageType type,
                   @JsonProperty("requestId") String requestId,
                   @JsonProperty("roomId") String roomId,
                   @JsonProperty("payload") ObjectNode payload) {
        this.type = Objects.requireNonNull(type, "type");
        if (requestId != null && !requestId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid requestId");
        }
        if (roomId != null && !roomId.matches("[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid roomId");
        }
        this.requestId = requestId;
        this.roomId = roomId;
        if (payload == null) {
            this.payload = JsonNodeFactory.instance.objectNode();
        } else {
            this.payload = payload.deepCopy();
        }
    }

    public MessageType getType() {
        return type;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getRoomId() {
        return roomId;
    }

    /** Trả bản sao để nơi nhận không vô tình sửa dữ liệu đã chuẩn bị gửi. */
    public ObjectNode getPayload() {
        return payload.deepCopy();
    }

    // Giữ cách gọi cũ để code của người 1 và 2 vẫn biên dịch được.
    public MessageType type() {
        return getType();
    }

    public String requestId() {
        return getRequestId();
    }

    public String roomId() {
        return getRoomId();
    }

    public ObjectNode payload() {
        return getPayload();
    }

    // So sánh nội dung của hai thông điệp, dùng khi kiểm tra encode/decode.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Message)) {
            return false;
        }
        Message message = (Message) other;
        return type == message.type
                && Objects.equals(requestId, message.requestId)
                && Objects.equals(roomId, message.roomId)
                && Objects.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + Objects.hashCode(requestId);
        result = 31 * result + Objects.hashCode(roomId);
        result = 31 * result + payload.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Message[type=" + type + ", requestId=" + requestId
                + ", roomId=" + roomId + ", payload=" + payload + "]";
    }
}
