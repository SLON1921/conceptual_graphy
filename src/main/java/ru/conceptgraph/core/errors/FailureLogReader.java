package ru.conceptgraph.core.errors;

import ru.conceptgraph.modules.infrastructure.repository.ConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение журнала ошибок entity_failures для вкладки «Ошибки» в окне.
 */
public final class FailureLogReader {

    public record Failure(String word, String message, String happenedAt) {
    }

    private FailureLogReader() {
    }

    /**
     * @return последние ошибки, новые сверху
     * @throws Exception если база недоступна (окно покажет это сообщение)
     */
    public static List<Failure> loadRecent(int limit) throws Exception {
        String sql = "SELECT word, error_message, happened_at FROM entity_failures "
                + "ORDER BY happened_at DESC LIMIT ?";
        List<Failure> result = new ArrayList<>();
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Failure(rs.getString(1), rs.getString(2), String.valueOf(rs.getTimestamp(3))));
                }
            }
        } catch (LinkageError e) {
            throw new Exception("база недоступна: " + e.getMessage(), e);
        }
        return result;
    }
}
