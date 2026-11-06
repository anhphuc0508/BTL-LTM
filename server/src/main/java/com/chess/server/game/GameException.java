package com.chess.server.game;

final class GameException extends RuntimeException {
    private final String code;

    GameException(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
