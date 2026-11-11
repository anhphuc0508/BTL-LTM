package com.chess.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public class GameFrame extends JFrame {

    private final ChessBoardPanel board = new ChessBoardPanel();
    private final MoveHistoryPanel history = new MoveHistoryPanel();

    private final JLabel playerInfo = new JLabel();
    private final JLabel status = new JLabel("Đang chờ trạng thái...");
    private final JButton resignButton = new JButton("Đầu hàng");

    public GameFrame(Runnable onResign, Runnable onDisconnect) {
        super("Chess Online");

        Color background = new Color(48, 46, 43);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(background);

        JPanel header = new JPanel(new BorderLayout(0, 12));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        playerInfo.setForeground(Color.WHITE);
        playerInfo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        playerInfo.putClientProperty("html.disable", Boolean.TRUE);

        status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        status.setForeground(Color.WHITE);
        status.setBackground(new Color(65, 63, 58));
        status.setOpaque(true);
        status.setHorizontalAlignment(JLabel.CENTER);
        status.setBorder(new EmptyBorder(14, 16, 14, 16));
        status.putClientProperty("html.disable", Boolean.TRUE);

        header.add(playerInfo, BorderLayout.NORTH);
        header.add(status, BorderLayout.CENTER);

        content.add(header, BorderLayout.NORTH);
        content.add(board, BorderLayout.CENTER);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(new Color(38, 37, 34));
        side.add(history, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 8));
        buttons.setOpaque(false);
        buttons.setBorder(new EmptyBorder(0, 16, 16, 16));

        resignButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        resignButton.addActionListener(e -> {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Bạn muốn đầu hàng ván này?",
                    "Đầu hàng",
                    JOptionPane.YES_NO_OPTION
            );

            if (answer == JOptionPane.YES_OPTION) {
                onResign.run();
            }
        });

        JButton disconnectButton = new JButton("Ngắt kết nối");
        disconnectButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        disconnectButton.addActionListener(e -> askDisconnect(onDisconnect));

        buttons.add(resignButton);
        buttons.add(disconnectButton);

        side.add(buttons, BorderLayout.SOUTH);
        content.add(side, BorderLayout.EAST);

        setContentPane(content);
        setMinimumSize(new Dimension(960, 650));

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                askDisconnect(onDisconnect);
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    private void askDisconnect(Runnable action) {
        int answer = JOptionPane.showConfirmDialog(
                this,
                "Ngắt kết nối và trở về màn hình kết nối?",
                "Ngắt kết nối",
                JOptionPane.YES_NO_OPTION
        );

        if (answer == JOptionPane.YES_OPTION) {
            action.run();
        }
    }

    public ChessBoardPanel getBoardPanel() {
        return board;
    }

    public MoveHistoryPanel getHistoryPanel() {
        return history;
    }

    public void setPlayerInfo(String name, String color, String roomId) {
        playerInfo.setText(
                name + " • " + color + " • Phòng " + roomId
        );
    }

    public void setStatus(String text) {
        status.setText(text);
    }

    public void setControls(boolean canMove, boolean canResign) {
        board.setInputEnabled(canMove);
        resignButton.setEnabled(canResign);
        // Chỉ làm nổi bật khi Client đã sẵn sàng cho người chơi đi quân.
        status.setBackground(canMove ? new Color(48, 112, 57) : new Color(65, 63, 58));
        setTitle(canMove ? "ĐẾN LƯỢT BẠN — Chess Online" : "Chess Online");
    }
}
