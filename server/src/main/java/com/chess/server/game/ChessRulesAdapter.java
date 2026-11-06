package com.chess.server.game;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

/** A room owns one adapter. Access must be guarded by that room's lock. */
public final class ChessRulesAdapter {
    public enum PositionStatus {
        IN_PROGRESS, CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL,
        FIFTY_MOVE_RULE, THREEFOLD_REPETITION
    }

    private final Board board = new Board();

    public ChessRulesAdapter() {
    }

    /** For server-side fixtures/imports only; the wire protocol never accepts a FEN from a client. */
    public ChessRulesAdapter(String fen) {
        board.loadFromFen(fen);
    }

    public boolean applyMove(String uci) {
        if (uci == null || !uci.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            return false;
        }
        Move move = new Move(uci, board.getSideToMove());
        // doMove alone does not check piece movement geometry. Check the generated legal moves first.
        if (!board.legalMoves().contains(move)) {
            return false;
        }
        return board.doMove(move, true);
    }

    public String getFen() {
        return board.getFen();
    }

    public PlayerColor sideToMove() {
        return PlayerColor.valueOf(board.getSideToMove().name());
    }

    public boolean isCheck() {
        return board.isKingAttacked();
    }

    public PositionStatus getStatus() {
        if (board.isMated()) {
            return PositionStatus.CHECKMATE;
        }
        if (board.isStaleMate()) {
            return PositionStatus.STALEMATE;
        }
        if (board.isInsufficientMaterial()) {
            return PositionStatus.INSUFFICIENT_MATERIAL;
        }
        if (board.getHalfMoveCounter() >= 100) {
            return PositionStatus.FIFTY_MOVE_RULE;
        }
        if (board.isRepetition()) {
            return PositionStatus.THREEFOLD_REPETITION;
        }
        return PositionStatus.IN_PROGRESS;
    }
}
