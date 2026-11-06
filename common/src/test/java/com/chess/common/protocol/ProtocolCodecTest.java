package com.chess.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.json.JsonMapper;

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

    @Test
    void classAndGettersPreserveOriginalJsonContract() throws Exception {
        Message message = new Message(MessageType.MOVE, "m1", "ABC234",
                codec.newPayload().put("move", "e2e4"));
        String expected = "{\"type\":\"MOVE\",\"requestId\":\"m1\",\"roomId\":\"ABC234\",\"payload\":{\"move\":\"e2e4\"}}\n";
        assertEquals(expected, codec.encode(message));

        // New getters and the old accessors can coexist in the two team members' code.
        Message received = codec.decode(expected.stripTrailing());
        assertEquals(message.type(), received.getType());
        assertEquals(message.requestId(), received.getRequestId());
        assertEquals(message.roomId(), received.getRoomId());
        assertEquals(message.payload(), received.getPayload());
        received.getPayload().put("move", "forged");
        assertEquals("e2e4", received.payload().path("move").asText());

        // Plain Jackson callers can still deserialize the envelope as with the original record.
        assertEquals(message, new JsonMapper().readValue(expected, Message.class));
        assertEquals("{\"type\":\"PING\",\"payload\":{}}\n",
                codec.encode(new Message(MessageType.PING, null, null, null)));
    }
}
