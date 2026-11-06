package com.chess.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ProtocolCodec {
    public static final int MAX_LINE_BYTES = 1_048_576;
    private static final Set<String> FIELDS = Set.of("type", "requestId", "roomId", "payload");
    private final JsonMapper mapper;

    public ProtocolCodec() {
        mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(MAX_LINE_BYTES).maxNumberLength(64).build());
    }

    /** Đổi một dòng JSON (đã bỏ ký tự xuống dòng) thành Message. */
    public Message decode(String line) {
        if (line == null || line.isBlank()) {
            throw new ProtocolException("BAD_MESSAGE");
        }
        if (line.length() > MAX_LINE_BYTES || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ProtocolException("BAD_MESSAGE");
        }
        try {
            JsonNode root = mapper.readTree(line);
            if (root == null || !root.isObject()) {
                throw new ProtocolException("BAD_MESSAGE");
            }
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                if (!FIELDS.contains(field)) {
                    throw new ProtocolException("BAD_MESSAGE");
                }
            }
            String type = optionalText(root, "type");
            if (type == null) {
                throw new ProtocolException("BAD_MESSAGE");
            }
            JsonNode payload = root.get("payload");
            if (payload != null && !payload.isObject()) {
                throw new ProtocolException("BAD_MESSAGE");
            }
            return new Message(MessageType.valueOf(type), optionalText(root, "requestId"),
                    optionalText(root, "roomId"), (ObjectNode) payload);
        } catch (ProtocolException ex) {
            throw ex;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new ProtocolException("BAD_MESSAGE");
        }
    }

    /** Đổi Message thành JSON và thêm đúng một ký tự xuống dòng để gửi qua socket. */
    public String encode(Message message) {
        Objects.requireNonNull(message, "message");
        try {
            String json = mapper.writeValueAsString(message);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                throw new ProtocolException("MESSAGE_TOO_LARGE");
            }
            return json + "\n";
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to encode message", ex);
        }
    }

    public ObjectNode newPayload() {
        return mapper.createObjectNode();
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ProtocolException("BAD_MESSAGE");
        }
        return value.textValue();
    }
}
