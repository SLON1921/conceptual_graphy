package ru.conceptgraph.shared;

public record Edge(Long sourceConceptId, Long targetConceptId, double weight, String label) {

    // Кастомный конструктор для обратной совместимости, если где-то в коде остался вызов без label
    public Edge(Long sourceConceptId, Long targetConceptId, double weight) {
        this(sourceConceptId, targetConceptId, weight, "связан");
    }
}
