package com.chess.common.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest {
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void parsesPlainMoveWithoutJson() {
        Message move = codec.decode("MOVE|ABC234|e2e4");
        assertEquals(MessageType.MOVE, move.getType());
        assertArrayEquals(new String[]{"ABC234", "e2e4"}, move.getFields());
        assertEquals("MOVE|ABC234|e2e4", codec.encode(move));
    }

    @Test
    void roundTripsUnicodeSpacesAndEmptyFields() {
        Message name = codec.decode("CREATE_ROOM|Phúc Chess");
        assertEquals("Phúc Chess", name.getField(0));
        assertEquals("CREATE_ROOM|Phúc Chess", codec.encode(name));
        String line = "STATE|ABC234|WHITE|WAITING|WHITE|0|8/8/8/8/8/8/8/8 w - - 0 1||||";
        assertEquals(line, codec.encode(codec.decode(line)));
        assertEquals("", codec.decode(line).getField(9));
        assertFalse(codec.encode(name).contains("\n"));
    }

    @Test
    void pingHasNoArgumentsAndFieldsCannotBeMutatedThroughReferences() {
        assertEquals(0, codec.decode("PING").getFieldCount());
        String[] fields = {"ABC234", "e2e4"};
        Message move = new Message(MessageType.MOVE, fields);
        fields[1] = "e2e5";
        move.getFields()[1] = "e2e6";
        assertEquals("e2e4", move.getField(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "UNKNOWN", "ping", "|PING", " PING", "PING ", "PING\n", "PING\r", "PING\nMOVE|ABC234|e2e4"})
    void rejectsMalformedLines(String line) {
        assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class, () -> codec.decode(line)).getCode());
    }

    @Test
    void rejectsNullAndEnforcesLimitInUtf8Bytes() {
        assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class, () -> codec.decode(null)).getCode());
        assertEquals("MESSAGE_TOO_LARGE", assertThrows(ProtocolException.class,
                () -> codec.decode("CREATE_ROOM|" + "ờ".repeat(6_000))).getCode());
        String boundary = "CREATE_ROOM|" + "a".repeat(ProtocolCodec.MAX_LINE_BYTES - 12);
        assertEquals(boundary, codec.encode(codec.decode(boundary)));
        assertThrows(ProtocolException.class, () -> codec.decode(boundary + "a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a|b", "a\nb", "a\rb"})
    void rejectsDelimiterInjectionWhileEncoding(String field) {
        assertThrows(ProtocolException.class, () -> codec.encode(new Message(MessageType.CREATE_ROOM, field)));
    }

    @Test
    void rejectsNullFieldWhileEncoding() {
        assertThrows(ProtocolException.class,
                () -> codec.encode(new Message(MessageType.CREATE_ROOM, new String[]{null})));
    }
}
