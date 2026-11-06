package com.chess.server.game;

public enum PlayerColor {
    WHITE, BLACK;

    public PlayerColor opposite() {
        return this == WHITE ? BLACK : WHITE;
    }
}
