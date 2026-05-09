package com.faceless.ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
@RequiredArgsConstructor
@Slf4j
public class YoutubeMigrationFixJob {

    private final DataSource dataSource;

    @PostConstruct
    public void run() {
        try (Connection conn = dataSource.getConnection()) {

            if (!tableExists(conn, "youtube_uploads")) {
                log.info("youtube_uploads does not exist, skipping migration");
                return;
            }

            migrate(conn);

        } catch (Exception e) {
            throw new RuntimeException("Migration failed", e);
        }
    }

    private void migrate(Connection conn) throws SQLException {
        String sql = """
            INSERT INTO social_uploads (
                id, video_id, platform, provider_post_id, status,
                uploaded_at, created_by, created_on, last_modified_by, last_modified_on
            )
            SELECT
                id, video_id, 'YOUTUBE', youtube_video_id, status,
                uploaded_at, created_by, created_on, last_modified_by, last_modified_on
            FROM youtube_uploads
        """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            stmt.execute("DROP TABLE IF EXISTS youtube_uploads");
        }

        log.info("YouTube migration executed successfully");
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }
}