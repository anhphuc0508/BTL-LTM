package com.chess.server.game;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Optional per-match JSON Lines archive, separate from technical logs and in-memory history. */
public final class MatchEventWriter {
    private static final Logger LOG = LoggerFactory.getLogger(MatchEventWriter.class);
    private final Path directory;

    public MatchEventWriter(Path directory) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    public static MatchEventWriter disabled() {
        return new MatchEventWriter(null);
    }

    // Calls for a match are serialized by its room lock. Short-lived streams avoid leaking file handles.
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
            // A disk failure must not cause an already-applied move to appear rejected to clients.
            LOG.error("event=MATCH_LOG_WRITE_FAILED matchId={}", matchId, ex);
        }
    }
}
