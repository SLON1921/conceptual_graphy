package ru.conceptgraph.shared;

public record ConceptEntity(
        Long id, // Добавили поле для хранения реального ID из PostgreSQL
        String value,
        EntityType type,
        int startChar,
        int endChar
) {
    // Кастомный конструктор для совместимости, если где-то сущность создается без ID (например, сразу после парсинга текста)
    public ConceptEntity(String value, EntityType type, int startChar, int endChar) {
        this(-1L, value, type, startChar, endChar);
    }
}
