package com.chess.server.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.chess.server.game.ChessRulesAdapter.PositionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ChessRulesAdapterTest {
    @ParameterizedTest
    @ValueSource(strings = {"e2e5", "a1a4", "e1e3", "c1h6", "g1g3", "e7e5", "a3a4", "e2e4q", "E2E4", "e2e4extra", "e2e4\n"})
    void rejectsIllegalMovementWithoutChangingFen(String move) {
        ChessRulesAdapter rules = new ChessRulesAdapter();
        String before = rules.getFen();
        assertFalse(rules.applyMove(move));
        assertEquals(before, rules.getFen());
        assertEquals(PlayerColor.WHITE, rules.sideToMove());
    }

    @Test
    void acceptsOpeningAndChangesTurn() {
        ChessRulesAdapter rules = new ChessRulesAdapter();
        assertTrue(rules.applyMove("e2e4"));
        assertEquals(PlayerColor.BLACK, rules.sideToMove());
        assertTrue(rules.getFen().startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq "));
        assertEquals(IN_PROGRESS, rules.getStatus());
    }

    @Test
    void handlesBothCastlesAndRejectsCastlingThroughCheck() {
        ChessRulesAdapter rules = new ChessRulesAdapter("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertTrue(rules.applyMove("e1g1"));
        assertTrue(rules.applyMove("e8c8"));
        assertTrue(rules.getFen().startsWith("2kr3r/8/8/8/8/8/8/R4RK1"));
        ChessRulesAdapter checked = new ChessRulesAdapter("r3kr1r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertFalse(checked.applyMove("e1g1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"q", "r", "b", "n"})
    void supportsAllPromotionsForBothColors(String promotion) {
        ChessRulesAdapter white = new ChessRulesAdapter("7k/P7/8/8/8/8/8/7K w - - 0 1");
        assertFalse(white.applyMove("a7a8"));
        assertTrue(white.applyMove("a7a8" + promotion));
        assertTrue(white.getFen().startsWith(promotion.toUpperCase() + "6k"));
        ChessRulesAdapter black = new ChessRulesAdapter("7k/8/8/8/8/8/p7/7K b - - 0 1");
        assertTrue(black.applyMove("a2a1" + promotion));
        assertTrue(black.getFen().contains("/" + promotion + "6K w "));
    }

    @Test
    void handlesEnPassantAndItsExpiry() {
        ChessRulesAdapter rules = new ChessRulesAdapter();
        for (String move : new String[]{"e2e4", "a7a6", "e4e5", "d7d5"}) {
            assertTrue(rules.applyMove(move));
        }
        assertTrue(rules.applyMove("e5d6"));
        assertTrue(rules.getFen().startsWith("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR"));
        ChessRulesAdapter expired = new ChessRulesAdapter();
        for (String move : new String[]{"e2e4", "a7a6", "e4e5", "d7d5", "h2h3", "h7h6"}) {
            assertTrue(expired.applyMove(move));
        }
        assertFalse(expired.applyMove("e5d6"));
    }

    @Test
    void forbidsMovesLeavingKingInCheck() {
        ChessRulesAdapter rules = new ChessRulesAdapter("k3r3/8/8/8/8/8/4R3/4K3 w - - 0 1");
        assertFalse(rules.applyMove("e2f2"));
    }

    @Test
    void detectsMateAndDrawConditions() {
        ChessRulesAdapter mate = new ChessRulesAdapter();
        for (String move : new String[]{"f2f3", "e7e5", "g2g4", "d8h4"}) {
            assertTrue(mate.applyMove(move));
        }
        assertEquals(CHECKMATE, mate.getStatus());
        assertTrue(mate.isCheck());
        assertEquals(STALEMATE, new ChessRulesAdapter("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").getStatus());
        assertEquals(INSUFFICIENT_MATERIAL, new ChessRulesAdapter("7k/8/6K1/8/8/8/8/8 w - - 0 1").getStatus());
        assertEquals(FIFTY_MOVE_RULE, new ChessRulesAdapter("7k/8/6K1/8/8/8/8/R7 w - - 100 51").getStatus());
    }

    @Test
    void keepsPositionHistoryForThreefoldRepetition() {
        ChessRulesAdapter rules = new ChessRulesAdapter();
        for (int cycle = 0; cycle < 2; cycle++) {
            for (String move : new String[]{"g1f3", "g8f6", "f3g1", "f6g8"}) {
                assertTrue(rules.applyMove(move));
            }
        }
        assertEquals(THREEFOLD_REPETITION, rules.getStatus());
    }
}
