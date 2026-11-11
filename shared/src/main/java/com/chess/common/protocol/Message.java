package com.chess.common.protocol;

import java.util.Objects;

/** Một dòng đã tách: loại lệnh và các chuỗi nằm sau dấu |. */
public final class Message {
    private final MessageType type;
    private final String[] fields;

    public Message(MessageType type, String... fields) {
        this.type = Objects.requireNonNull(type, "type");
        this.fields = fields.clone();
    }

    public MessageType getType() { return type; }
    public int getFieldCount() { return fields.length; }
    public String getField(int index) { return fields[index]; }
    public String[] getFields() { return fields.clone(); }
}
