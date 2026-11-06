package com.chess.server.game;

public enum PlayerColor {
    WHITE, BLACK;

    public PlayerColor opposite() {
        if (this == WHITE) {
            return BLACK;
        }
        return WHITE;
    }
}
