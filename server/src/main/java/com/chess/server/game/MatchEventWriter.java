package com.chess.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Lưu nhật ký chuỗi vào logs/matches/<matchId>.log. */
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

    // GameService đã khóa phòng trước khi ghi.
    public void write(String matchId, String event) {
        if (directory == null) {
            return;
        }
        if (matchId == null || !matchId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid server-generated matchId");
        }
        if (event == null || event.indexOf('\n') >= 0 || event.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Event must be one line");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(matchId + ".log"), event + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException ex) {
            // Lỗi ghi file không làm một nước hợp lệ bị từ chối.
            LOG.error("event=MATCH_LOG_WRITE_FAILED matchId={}", matchId, ex);
        }
    }
}
