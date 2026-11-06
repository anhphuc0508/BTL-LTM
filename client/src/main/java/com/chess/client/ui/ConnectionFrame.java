package com.chess.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import org.slf4j.bridge.SLF4JBridgeHandler;

import com.chess.client.ChessClient;

public class ConnectionFrame extends JFrame {

    public interface ConnectAction {
        void connect(String host, int port, String playerName);
    }

    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JTextField portField = new JTextField("5000");
    private final JTextField nameField = new JTextField();

    private final JButton connectButton = new JButton("Kết nối");
    private final JLabel status = new JLabel("Nhập thông tin Server");

    public ConnectionFrame(ConnectAction action, Runnable onExit) {
        super("Chess Online — Kết nối");

        Color background = new Color(38, 37, 34);
        Color foreground = new Color(235, 234, 230);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBorder(new EmptyBorder(26, 30, 26, 30));
        content.setBackground(background);

        JLabel title = new JLabel("CHESS ONLINE");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
        title.setForeground(foreground);
        content.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        form.setOpaque(false);

        addField(form, "Địa chỉ Server", hostField);
        addField(form, "Cổng", portField);
        addField(form, "Tên người chơi", nameField);

        connectButton.setBackground(new Color(115, 149, 82));
        connectButton.setForeground(Color.WHITE);
        connectButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        connectButton.setFocusPainted(false);

        form.add(connectButton);
        content.add(form, BorderLayout.CENTER);

        status.setForeground(new Color(190, 188, 180));
        content.add(status, BorderLayout.SOUTH);

        connectButton.addActionListener(e -> {
            String host = hostField.getText().trim();
            String name = nameField.getText().strip();

            if (host.isEmpty()) {
                showError("Bạn chưa nhập địa chỉ Server.");
                return;
            }

            if (name.isEmpty() || name.codePointCount(0, name.length()) > 40 || name.contains("|")
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                showError("Tên phải có 1–40 ký tự, không chứa | hoặc ký tự điều khiển.");
                return;
            }

            try {
                int port = Integer.parseInt(portField.getText().trim());

                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }

                action.connect(host, port, name);

            } catch (NumberFormatException ex) {
                showError("Port phải là số từ 1 đến 65535.");
            }
        });

        setContentPane(content);
        getRootPane().setDefaultButton(connectButton);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onExit.run();
            }
        });

        setSize(440, 420);
        setResizable(false);
        setLocationRelativeTo(null);
    }

    private void addField(
            JPanel panel, String title, JTextField field) {

        JLabel label = new JLabel(title);
        label.setForeground(new Color(220, 218, 210));

        field.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));

        panel.add(label);
        panel.add(field);
    }

    public void setBusy(boolean busy) {
        hostField.setEnabled(!busy);
        portField.setEnabled(!busy);
        nameField.setEnabled(!busy);
        connectButton.setEnabled(!busy);

        status.setText(busy
                ? "Đang kết nối..."
                : "Nhập thông tin Server");
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(
                this, message, "Thông báo", JOptionPane.WARNING_MESSAGE
        );
    }
    public static void main(String[] args) {
    // Chạy hai Client cùng máy vẫn có hai file log riêng.
        System.setProperty(
                "client.pid",
                Long.toString(ProcessHandle.current().pid())
        );

        // Chuyển log FEN của ChessBoardPanel sang SLF4J/Logback.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        SwingUtilities.invokeLater(() -> {
            ChessClient client = new ChessClient();
            client.start();
        });
    }
}
