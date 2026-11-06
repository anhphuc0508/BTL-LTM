package com.chess.server.game;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Lưu nhật ký từng ván vào file JSON Lines, tách riêng với technical log và lịch sử trong bộ nhớ. */
public final class MatchEventWriter {
    private static final Logger LOG = LoggerFactory.getLogger(MatchEventWriter.class);
    private final Path directory;

    public MatchEventWriter(Path directory) {
        if (directory == null) {
            this.directory = null;
        } else {
            this.directory = directory.toAbsolutePath().normalize();
        }
    }

    public static MatchEventWriter disabled() {
        return new MatchEventWriter(null);
    }

    // GameService đã khóa phòng trước khi ghi. Mỗi lần ghi xong sẽ đóng file ngay.
    public void write(String matchId, ObjectNode event) {
        if (directory == null) {
            return;
        }
        if (matchId == null || !matchId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid server-generated matchId");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(matchId + ".jsonl"), event.toString() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException ex) {
            // Lỗi ghi file không được biến một nước đã hợp lệ thành phản hồi từ chối.
            LOG.error("event=MATCH_LOG_WRITE_FAILED matchId={}", matchId, ex);
        }
    }
}
