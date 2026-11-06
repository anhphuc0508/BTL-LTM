package com.chess.common.protocol;

/** Mã lỗi khi dòng chuỗi không hợp lệ; không chứa dữ liệu thô của Client. */
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
