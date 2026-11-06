package com.chess.server.game;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

/** Nối code của nhóm với thư viện luật cờ Chesslib. Mỗi phòng có một bàn cờ riêng. */
public final class ChessRulesAdapter {
    public enum PositionStatus {
        IN_PROGRESS, CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL,
        FIFTY_MOVE_RULE, THREEFOLD_REPETITION
    }

    private final Board board = new Board();

    public ChessRulesAdapter() {
    }

    /** Nạp vị trí để kiểm thử phía Server. Client không được tự gửi FEN để thay bàn cờ. */
    public ChessRulesAdapter(String fen) {
        board.loadFromFen(fen);
    }

    public boolean applyMove(String uci) {
        if (uci == null || !uci.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            return false;
        }
        Move move = new Move(uci, board.getSideToMove());
        // Kiểm tra nước đi nằm trong danh sách hợp lệ trước khi thay đổi bàn cờ.
        if (!board.legalMoves().contains(move)) {
            return false;
        }
        return board.doMove(move, true);
    }

    public String getFen() {
        return board.getFen();
    }

    public PlayerColor sideToMove() {
        return getSideToMove();
    }

    public PlayerColor getSideToMove() {
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
