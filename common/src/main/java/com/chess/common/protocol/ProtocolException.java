package com.chess.common.protocol;

/** Safe error code for a malformed envelope; never includes the raw client input. */
public final class ProtocolException extends IllegalArgumentException {
    private final String code;

    public ProtocolException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return getCode();
    }

    public String getCode() {
        return code;
    }
}
