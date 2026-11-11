package com.chess.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

public class LobbyFrame extends JFrame {

    private final JButton createButton = new JButton("Tạo phòng mới");
    private final JButton joinButton = new JButton("Vào phòng");
    private final JTextField roomField = new JTextField();

    private final JLabel roomLabel = new JLabel("Chưa tham gia phòng");
    private final JLabel status = new JLabel("Tạo phòng hoặc nhập mã phòng");

    public LobbyFrame(
            String playerName,
            Runnable onCreate,
            Consumer<String> onJoin,
            Runnable onDisconnect) {

        super("Chess Online — Phòng chờ");

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBackground(new Color(38, 37, 34));
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        JLabel title = new JLabel();
        title.setText("Người chơi: " + playerName);
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setForeground(Color.WHITE);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));

        content.add(title, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(0, 1, 0, 10));
        controls.setOpaque(false);

        styleButton(createButton);
        styleButton(joinButton);

        createButton.addActionListener(e -> onCreate.run());

        joinButton.addActionListener(e -> {
            String roomId = roomField.getText().strip().toUpperCase(java.util.Locale.ROOT);

            if (!roomId.matches("[A-Z0-9]{6}")) {
                JOptionPane.showMessageDialog(
                        this, "Mã phòng phải gồm đúng 6 chữ cái in hoa hoặc chữ số."
                );
                return;
            }

            onJoin.accept(roomId);
        });

        JLabel hint = new JLabel("Mã phòng");
        hint.setForeground(Color.LIGHT_GRAY);
        hint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        roomField.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));

        roomLabel.setForeground(new Color(174, 207, 141));
        roomLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        roomLabel.putClientProperty("html.disable", Boolean.TRUE);

        controls.add(createButton);
        controls.add(hint);
        controls.add(roomField);
        controls.add(joinButton);
        controls.add(roomLabel);

        content.add(controls, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        bottom.setOpaque(false);

        status.setForeground(Color.LIGHT_GRAY);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        status.putClientProperty("html.disable", Boolean.TRUE);
        bottom.add(status, BorderLayout.NORTH);

        JButton disconnectButton = new JButton("Ngắt kết nối");
        disconnectButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        disconnectButton.addActionListener(e -> onDisconnect.run());
        bottom.add(disconnectButton, BorderLayout.SOUTH);

        content.add(bottom, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onDisconnect.run();
            }
        });

        setSize(540, 540);
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private void styleButton(JButton button) {
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        button.setBackground(new Color(115, 149, 82));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
    }

    public void setBusy(boolean busy) {
        createButton.setEnabled(!busy);
        joinButton.setEnabled(!busy);
        roomField.setEnabled(!busy);

        status.setText(busy
                ? "Đang chờ Server..."
                : "Tạo phòng hoặc nhập mã phòng");
    }

    public void showRoom(String roomId) {
        setBusy(true);
        roomLabel.setText("Mã phòng: " + roomId);
        status.setText("Đang chờ đối thủ tham gia...");
    }
}
