package ru.conceptgraph.core.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import ru.conceptgraph.modules.infrastructure.repository.ConnectionPool;
import java.sql.Connection;
import java.sql.SQLException;


public class AppExceptionHandler implements Thread.UncaughtExceptionHandler{
    private static final Logger log = LoggerFactory.getLogger(AppExceptionHandler.class);

    @Override
    public void uncaughtException(Thread thread, Throwable e) {
        log.error("Критическая неперехваченная ошибка в потоке: {}", thread.getName(), e);
        logFailureToDB("SYSTEM_THREAD_"+ thread.getName(), e.getMessage());
    }

    public void CatchTheFailure(String concept, Throwable error) {
        System.err.println("[ERROR-LOG] Сбой обработки сущности '" + concept + "': " + error.getMessage());
        logFailureToDB(concept, error.getMessage());
    }

    private void logFailureToDB(String word, String error) {
        String sql = "INSERT INTO entity_failures (word, error_message) VALUES (?, ?)";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, truncate(word, 255));
            pstmt.setString(2, truncate(error, 1000));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("КРИТИЧЕСКИЙ СБОЙ: Не удалось записать лог ошибки в PostgreSQL!", e);
        }
    }

    private String truncate(String text, int maxLength){
        if (text == null) return "NULL";
        return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
    }
}
