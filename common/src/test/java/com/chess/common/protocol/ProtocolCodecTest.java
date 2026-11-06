package com.chess.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest {
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void roundTripsUnicodeAndProducesExactlyOneTerminator() {
        Message input = new Message(MessageType.CREATE_ROOM, "req-1", null,
                codec.newPayload().put("playerName", "Phúc \"Chess\""));
        String line = codec.encode(input);
        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.chars().filter(c -> c == '\n').count());
        assertEquals(input, codec.decode(line.substring(0, line.length() - 1)));
    }

    @Test
    void permitsOmittedOptionalFields() {
        assertEquals(new Message(MessageType.PING, null, null, null), codec.decode("{\"type\":\"PING\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{}", "{", "{\"type\":4}",
            "{\"type\":\"BOGUS\"}", "{\"type\":\"PING\",\"payload\":[]}",
            "{\"type\":\"PING\",\"payload\":null}", "{\"type\":\"PING\",\"requestId\":4}",
            "{\"type\":\"PING\",\"roomId\":\"bad\"}",
            "{\"type\":\"PING\",\"extra\":true}", "{\"type\":\"PING\",\"type\":\"MOVE\"}",
            "{\"type\":\"PING\"} {}", "{\n\"type\":\"PING\"}",
            "{\"type\":\"PING\",\"requestId\":\"bad\\nlog\"}"})
    void rejectsMalformedOrAmbiguousEnvelopes(String line) {
        assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class, () -> codec.decode(line)).code());
    }

    @Test
    void enforcesUtf8ByteLimitAndDepth() {
        String tooLarge = "{\"type\":\"PING\",\"payload\":{\"x\":\"" + "ờ".repeat(400_000) + "\"}}";
        assertEquals("MESSAGE_TOO_LARGE", assertThrows(ProtocolException.class, () -> codec.decode(tooLarge)).code());
        String deep = "{\"type\":\"PING\",\"payload\":{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}}";
        assertThrows(ProtocolException.class, () -> codec.decode(deep));
    }

    @Test
    void payloadCannotBeChangedThroughCallerReferences() {
        var original = codec.newPayload().put("move", "e2e4");
        Message message = new Message(MessageType.MOVE, "r1", "ABC234", original);
        original.put("move", "e2e5");
        message.payload().put("move", "e2e6");
        assertEquals("e2e4", message.payload().path("move").asText());
    }
}
