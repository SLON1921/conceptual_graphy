package ru.conceptgraph.modules.infrastructure.repository;

import java.sql.*;

import ru.conceptgraph.shared.EntityType;
import ru.conceptgraph.shared.Edge;
import java.util.List;

/**
 * Репозиторий инфраструктурного слоя для управления данными в СУБД PostgreSQL.
 * Обеспечивает высокопроизводительную пакетную обработку, транзакционную целостность
 * и мапинг типов данных Java на кастомные перечисления СУБД.
 *
 * @author Рома (Разработчик Б)
 * @version 1.1
 * @since Этап 1
 */
public class ConceptRepository {
    /**
     * Сохраняет метаданные обрабатываемого файла в таблицу 'documents'.
     * При обнаружении дубликата пути (path) обновляет метку времени парсинга.
     *
     * @param name Название файла с расширением (например, "report.pdf").
     * @param path Абсолютный уникальный путь к файлу на жестком диске.
     * @param size Физический размер файла в байтах.
     * @return Сгенерированный или существующий числовой ID документа (первичный ключ).
     * @throws SQLException Если произошло критическое падение сессии или структуры БД.
     */
    public static Long saveDocument(String name, String path, Long size) throws SQLException {
        String sql = "INSERT INTO documents (name, path, size) VALUES (?, ?, ?) " +
                "ON CONFLICT (path) DO UPDATE SET parsed_at = CURRENT_TIMESTAMP RETURNING id";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, path);
            pstmt.setLong(3, size);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Ошибка сохранения документа в БД:" + path);
    }

    /**
     * Сохраняет уникальный очищенный термин в справочник понятий 'concepts'.
     * Автоматически мапит Java Enum на тип concept_type внутри PostgreSQL.
     * В случае дубликата слова возвращает ID ранее созданной записи.
     *
     * @param value Очищенное слово (понятие), найденное в тексте.
     * @param type Категория сущности, ограниченная строгим перечислением Java.
     * @return Уникальный числовой ID понятия из базы данных.
     * @throws SQLException При нарушении ограничений целостности СУБД.
     */
    public static Long saveConcept(String value, EntityType type) throws SQLException {
        String sql = "INSERT INTO concepts (value, type) VALUES (?, ?::concept_type) " +
                "ON CONFLICT (value) DO UPDATE SET value = EXCLUDED.value RETURNING id";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, value);
            pstmt.setString(2, type.name());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Ошибка сохранения понятия в БД:" + value);
    }

    /**
     * Регистрирует точную символьную координату вхождения слова в документ.
     * Если данное слово на этой же позиции в документе уже записано, запрос игнорируется.
     *
     * @param documentId Числовой ID документа из таблицы 'documents'.
     * @param conceptId Числовой ID слова из справочника 'concepts'.
     * @param position Символьное смещение (количество знаков) от начала файла.
     * @throws SQLException При падении сетевого соединения с PostgreSQL.
     */
    public static void saveOccurrences(Long documentId, Long conceptId, int position) throws SQLException {
        String sql = "INSERT INTO occurrences (document_id, concept_id, position) VALUES (?, ?, ?) " +
                "ON CONFLICT (document_id, concept_id, position) DO NOTHING;";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, documentId);
            pstmt.setLong(2, conceptId);
            pstmt.setInt(3, position);
            pstmt.executeUpdate();
        }
    }

    /**
     * Пакетное (Batch) сохранение рассчитанных ребер понятийного графа Вани.
     * Открывает ручную ACID-транзакцию, изолируя сеть от пошаговых дисковых микро-записей.
     * При совпадении ребер перезаписывает старый семантический вес актуальным усиленным значением.
     *
     * @param edges Список параметризованных объектов-рекордов ребер графа связей.
     * @throws SQLException При падении транзакции (автоматически вызывает rollback).
     */
    public static void saveGraphEdges(List<Edge> edges) throws SQLException {
        String sql = "INSERT INTO graph_edges (source_concept_id, target_concept_id, weight) VALUES (?, ?, ?) " +
                "ON CONFLICT (source_concept_id, target_concept_id) DO UPDATE SET weight = EXCLUDED.weight;";

        Connection conn = null;
        try {
            conn = ConnectionPool.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Edge edge : edges){
                    pstmt.setLong(1, edge.sourceConceptId());
                    pstmt.setLong(2, edge.targetConceptId());
                    pstmt.setDouble(3, edge.weight());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();

            }

        } catch (SQLException e) {
            if (conn != null) {
                conn.rollback();
            }
            throw e;
        } finally {
            if (conn != null) {
                conn.setAutoCommit(true);
                conn.close();
            }
        }
    }
}
