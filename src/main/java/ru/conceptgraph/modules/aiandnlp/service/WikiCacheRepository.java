package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.modules.infrastructure.repository.ConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Запись выбранной статьи Википедии в кэш wiki_cache (одна статья на понятие).
 */
public final class WikiCacheRepository {

    private WikiCacheRepository() {
    }

    public static void save(Long conceptId, String url, String snippet, int linksCount, int wordsCount)
            throws Exception {
        String sql = "INSERT INTO wiki_cache (concept_id, url, snippet, links_count, words_count) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (concept_id) DO UPDATE SET url = EXCLUDED.url, "
                + "snippet = EXCLUDED.snippet, links_count = EXCLUDED.links_count, words_count = EXCLUDED.words_count";
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, conceptId);
            pstmt.setString(2, url);
            pstmt.setString(3, snippet == null ? "" : snippet);
            pstmt.setInt(4, linksCount);
            pstmt.setInt(5, wordsCount);
            pstmt.executeUpdate();
        } catch (LinkageError e) {
            throw new Exception("база недоступна: " + e.getMessage(), e);
        }
    }
}
