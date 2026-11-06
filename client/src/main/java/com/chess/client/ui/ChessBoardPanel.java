package com.chess.client.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.util.Objects;
import java.util.logging.Logger;

import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Board view only. All public mutations must run on Swing's EDT. */
public class ChessBoardPanel extends JPanel {
    public static final String INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final Logger LOG = Logger.getLogger(ChessBoardPanel.class.getName());
    private static final Color LIGHT = new Color(235, 236, 208), DARK = new Color(115, 149, 82);
    private static final Color BACKGROUND = new Color(48, 46, 43);
    private final Font pieceFont = loadPieceFont();
    private char[] pieces = new char[64];
    private int selected = -1, hovered = -1, lastFrom = -1, lastTo = -1;
    private boolean flipped, inputEnabled = true;
    private MoveSelectionListener moveListener = (from, to) -> {};

    /** Zero-based board coordinates: row 0 = rank 8, column 0 = file a. */
    public record Square(int row, int column) {
        public Square {
            if (row < 0 || row > 7 || column < 0 || column > 7)
                throw new IllegalArgumentException("Square must be inside the board");
        }
    }
    @FunctionalInterface
    public interface MoveSelectionListener { void onMoveSelected(Square from, Square to); }

    public ChessBoardPanel() {
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(760, 760));
        setFocusable(true);
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !inputEnabled) return;
                requestFocusInWindow();
                int index = indexAt(e.getX(), e.getY());
                if (index >= 0) select(index);
            }
            @Override public void mouseMoved(MouseEvent e) {
                hovered = indexAt(e.getX(), e.getY());
                setCursor(Cursor.getPredefinedCursor(inputEnabled && hovered >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
            }
            @Override public void mouseExited(MouseEvent e) { hovered = -1; repaint(); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        getActionMap().put("clear", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { clearSelection(); }
        });
        setFen(INITIAL_FEN);
    }

    public void setMoveSelectionListener(MoveSelectionListener listener) { moveListener = Objects.requireNonNull(listener); }
    public void setFlipped(boolean value) { flipped = value; hovered = -1; repaint(); }
    public void setInputEnabled(boolean value) { inputEnabled = value; clearSelection(); }
    public void clearSelection() { selected = -1; repaint(); }
    public void setLastMove(Square from, Square to) {
        lastFrom = from == null ? -1 : from.row() * 8 + from.column();
        lastTo = to == null ? -1 : to.row() * 8 + to.column();
        repaint();
    }

    /** Updates display atomically. Does not validate chess rules. FEN is logged, never displayed. */
    public void setFen(String fen) {
        if (fen == null) throw new IllegalArgumentException("FEN is null");
        String[] fields = fen.trim().split("\\s+");
        if (fields.length != 6 || !fields[1].matches("[wb]")
                || !fields[2].matches("-|K?Q?k?q?") || fields[2].isEmpty()
                || !fields[3].matches("-|[a-h][36]")
                || !fields[4].matches("[0-9]+") || !fields[5].matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid FEN fields");
        String[] ranks = fields[0].split("/", -1);
        if (ranks.length != 8) throw new IllegalArgumentException("FEN requires 8 ranks");
        char[] next = new char[64];
        for (int row = 0; row < 8; row++) {
            int col = 0;
            boolean previousDigit = false;
            for (char p : ranks[row].toCharArray()) {
                if (p >= '1' && p <= '8') {
                    if (previousDigit) throw new IllegalArgumentException("Adjacent empty-square counts");
                    col += p - '0'; previousDigit = true;
                } else {
                    if ("prnbqkPRNBQK".indexOf(p) < 0 || col >= 8)
                        throw new IllegalArgumentException("Invalid piece or rank width");
                    next[row * 8 + col++] = p; previousDigit = false;
                }
                if (col > 8) throw new IllegalArgumentException("Rank exceeds 8 squares");
            }
            if (col != 8) throw new IllegalArgumentException("Rank must contain 8 squares");
        }
        pieces = next;
        selected = lastFrom = lastTo = -1;
        LOG.info("event=BOARD_STATE fen=" + String.join(" ", fields));
        repaint();
    }

    private void select(int target) {
        if (selected == target) selected = -1;
        else if (selected < 0) { if (pieces[target] != 0) selected = target; }
        else if (pieces[target] != 0 && Character.isUpperCase(pieces[target]) == Character.isUpperCase(pieces[selected])) selected = target;
        else {
            int source = selected;
            selected = -1;
            repaint();
            moveListener.onMoveSelected(new Square(source / 8, source % 8), new Square(target / 8, target % 8));
        }
        repaint();
    }

    private int cellSize() { return Math.max(1, (Math.min(getWidth(), getHeight()) - 40) / 8); }
    private int left() { return (getWidth() - cellSize() * 8) / 2; }
    private int top() { return (getHeight() - cellSize() * 8) / 2; }
    private int indexAt(int x, int y) {
        int dx = x - left(), dy = y - top(), size = cellSize() * 8;
        if (dx < 0 || dy < 0 || dx >= size || dy >= size) return -1;
        int display = (dy / cellSize()) * 8 + dx / cellSize();
        return flipped ? 63 - display : display;
    }
    @Override public String getToolTipText(MouseEvent e) {
        int i = indexAt(e.getX(), e.getY());
        return i < 0 ? null : "" + (char)('a' + i % 8) + (8 - i / 8);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int cell = cellSize(), x0 = left(), y0 = top(), size = cell * 8;
        g.setColor(new Color(0, 0, 0, 65));
        g.fillRoundRect(x0 - 5, y0 + 5, size + 10, size + 8, 12, 12);
        g.clip(new RoundRectangle2D.Double(x0, y0, size, size, 8, 8));
        for (int display = 0; display < 64; display++) {
            int i = flipped ? 63 - display : display;
            int row = display / 8, col = display % 8, x = x0 + col * cell, y = y0 + row * cell;
            boolean light = (row + col) % 2 == 0;
            g.setColor(light ? LIGHT : DARK);
            g.fillRect(x, y, cell, cell);
            if (i == lastFrom || i == lastTo || i == selected) {
                g.setColor(new Color(246, 230, 69, i == selected ? 165 : 105));
                g.fillRect(x, y, cell, cell);
            }
            if (i == hovered && inputEnabled) {
                g.setColor(new Color(255, 255, 255, 65));
                g.setStroke(new BasicStroke(3));
                g.drawRect(x + 2, y + 2, cell - 4, cell - 4);
            }
            if (pieces[i] != 0) drawPiece(g, pieces[i], x, y, cell);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, cell / 6)));
            g.setColor(light ? DARK.darker() : LIGHT);
            if (col == 0) g.drawString(String.valueOf(8 - i / 8), x + 4, y + g.getFontMetrics().getAscent() + 2);
            if (row == 7) g.drawString(String.valueOf((char)('a' + i % 8)), x + cell - g.getFontMetrics().charWidth('a') - 4, y + cell - 4);
        }
        g.dispose();
    }

    private void drawPiece(Graphics2D g, char piece, int x, int y, int cell) {
        int index = "kqrbnp".indexOf(Character.toLowerCase(piece));
        String glyph = String.valueOf((char)(0x265A + index));
        GlyphVector vector = pieceFont.deriveFont(cell * .90f).createGlyphVector(g.getFontRenderContext(), glyph);
        Shape raw = vector.getOutline();
        Rectangle2D bounds = raw.getBounds2D();
        AffineTransform position = AffineTransform.getTranslateInstance(
                x + (cell - bounds.getWidth()) / 2 - bounds.getX(),
                y + (cell - bounds.getHeight()) / 2 - bounds.getY());
        Shape shape = position.createTransformedShape(raw);
        boolean white = Character.isUpperCase(piece);
        g.setColor(new Color(0, 0, 0, 45));
        g.fill(AffineTransform.getTranslateInstance(0, cell * .025).createTransformedShape(shape));
        g.setStroke(new BasicStroke(Math.max(1.1f, cell * .019f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(white ? new Color(67, 65, 60) : new Color(205, 203, 190));
        g.draw(shape);
        g.setColor(white ? new Color(250, 249, 245) : new Color(48, 47, 44));
        g.fill(shape);
    }
    private static Font loadPieceFont() {
        try (InputStream stream = ChessBoardPanel.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (stream != null) return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (Exception ex) { LOG.warning("Unable to load bundled font: " + ex.getMessage()); }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 64);
    }
}
