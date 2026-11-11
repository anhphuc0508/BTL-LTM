package com.chess.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public class MoveHistoryPanel extends JPanel {

    private static final Color BACKGROUND = new Color(38, 37, 34);
    private static final Color ROW_DARK = new Color(45, 44, 41);
    private static final Color ROW_LIGHT = new Color(51, 50, 46);
    private static final Color TEXT = new Color(235, 234, 230);

    private final Map<Integer, String> moves = new TreeMap<>();

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Lượt", "Trắng", "Đen"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel("Chưa có nước đi");

    public MoveHistoryPanel() {
        setLayout(new BorderLayout(0, 16));
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 16));
        setPreferredSize(new Dimension(300, 600));

        JLabel title = new JLabel("LỊCH SỬ NƯỚC ĐI");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        title.setForeground(TEXT);
        add(title, BorderLayout.NORTH);

        table.setRowHeight(44);
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        table.setForeground(TEXT);
        table.setBackground(BACKGROUND);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setFocusable(false);

        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(0).setMaxWidth(60);

        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 36));
        table.getTableHeader().setDefaultRenderer(
                new DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(
                            JTable t, Object value, boolean selected,
                            boolean focused, int row, int column) {

                        super.getTableCellRendererComponent(
                                t, value, false, false, row, column
                        );

                        setBackground(BACKGROUND);
                        setForeground(new Color(165, 163, 155));
                        setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
                        setHorizontalAlignment(CENTER);
                        setBorder(BorderFactory.createEmptyBorder());
                        return this;
                    }
                }
        );

        table.setDefaultRenderer(Object.class,
                new DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(
                            JTable t, Object value, boolean selected,
                            boolean focused, int row, int column) {

                        super.getTableCellRendererComponent(
                                t, value, false, false, row, column
                        );

                        setHorizontalAlignment(CENTER);
                        setBackground(row % 2 == 0 ? ROW_DARK : ROW_LIGHT);
                        setForeground(column == 0
                                ? new Color(165, 163, 155) : TEXT);
                        setBorder(BorderFactory.createEmptyBorder());
                        return this;
                    }
                }
        );

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BACKGROUND);
        scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        add(scrollPane, BorderLayout.CENTER);

        summary.setForeground(new Color(165, 163, 155));
        summary.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        add(summary, BorderLayout.SOUTH);
    }

    /**
     * Chỉ gọi sau khi Server xác nhận nước đi.
     * ply: 1 = Trắng lượt 1, 2 = Đen lượt 1, 3 = Trắng lượt 2...
     * notation: SAN nếu Server cung cấp, nếu không dùng UCI.
     * Gọi trên Swing EDT.
     */
    public void addConfirmedMove(int ply, String notation) {
        if (ply < 1 || notation == null || notation.isBlank()) {
            throw new IllegalArgumentException("Nước đi không hợp lệ");
        }

        // Nhận lại cùng nước đi không tạo thêm dòng.
        if (notation.equals(moves.get(ply))) {
            return;
        }

        moves.put(ply, notation);

        int row = (ply - 1) / 2;
        int column = ply % 2 == 1 ? 1 : 2;

        while (model.getRowCount() <= row) {
            model.addRow(new Object[]{
                    model.getRowCount() + 1, "", ""
            });
        }

        model.setValueAt(notation, row, column);
        summary.setText(moves.size() + " nước đi đã xác nhận");

        table.scrollRectToVisible(
                table.getCellRect(row, column, true)
        );
    }

    /** Gọi khi bắt đầu ván mới, trên Swing EDT. */
    public void clearHistory() {
        moves.clear();
        model.setRowCount(0);
        summary.setText("Chưa có nước đi");
    }
}
