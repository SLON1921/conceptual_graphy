package ru.conceptgraph.modules.analytics.service;

import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
/**
 * Модуль Разработчика В (Аналитик).
 * Лабораторная 3 (Шаг 3).
 * Алгоритм GraphBuilder для расчета ребер графа по расстоянию в словах
 * и динамического усиления веса на основе предметного указателя.
 */
public class GraphBuilder {

    private final int maxWordDistance; // Пороговое значение N слов
    private final double indexBooster; // Коэффициент усиления за предметный указатель

    /**
     * Конструктор билдера графа.
     * @param maxWordDistance Максимальное расстояние в словах (N) для создания связи.
     * @param indexBooster Коэффициент, на который умножается вес при совпадении в индексе.
     */
    public GraphBuilder(int maxWordDistance, double indexBooster) {
        this.maxWordDistance = maxWordDistance;
        this.indexBooster = indexBooster;
    }

    /**
     * Основной метод построения связей графа в памяти.
     *
     * @param entities Список всех найденных сущностей в документе (от Разработчика А).
     * @param rawText Полный сырой текст документа для расчета расстояния в словах.
     * @param indexConcepts Набор уникальных ID понятий, извлеченных из "Предметного указателя".
     * @return Список рассчитанных ребер графа (Edge).
     */
    public List<Edge> buildGraphInMemory(List<ConceptEntity> entities, String rawText, Set<String> indexConcepts) {
        List<Edge> edges = new ArrayList<>();

        // Исправлено: возвращаем пустой список edges вместо 0
        if (entities == null || entities.size() < 2 || rawText == null || rawText.isEmpty()) {
            return edges;
        }

        // Вложенные циклы для обхода всех пар сущностей (i, j)
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                ConceptEntity entityA = entities.get(i);
                ConceptEntity entityB = entities.get(j);

                // Защита от связывания сущности самой с собой
                if (entityA.conceptId().equals(entityB.conceptId())) {
                    continue;
                }

                // 1. Расчет расстояния в словах между сущностями для проверки порога N
                int wordDistance = calculateWordDistance(rawText, entityA, entityB);

                if (wordDistance <= maxWordDistance) {
                    // 2. Расчет базового веса ребра по формуле из ТЗ: |position(A) - position(B)|
                    double weight = Math.abs(entityA.startChar() - entityB.startChar());

                    // 3. Динамическое усиление веса, если ОБА понятия есть в предметном указателе
                    if (indexConcepts != null &&
                            indexConcepts.contains(entityA.conceptId()) &&
                            indexConcepts.contains(entityB.conceptId())) {
                        weight *= indexBooster;
                    }

                    // Создаем ребро и добавляем в список
                    edges.add(new Edge(entityA.conceptId(), entityB.conceptId(), weight));
                }
            }
        }

        // Возвращаем наполненный список ребер графа
        return edges;
    }
    /**
     * Вспомогательный метод подсчета слов строго между двумя сущностями.
     *
     * @param text Полный сырой текст документа.
     * @param a Первая сущность.
     * @param b Вторая сущность.
     * @return Количество слов между сущностями. Если они перекрываются, возвращает 0.
     */
    private int calculateWordDistance(String text, ConceptEntity a, ConceptEntity b) {
        // Находим конец сущности, которая идет в тексте первой
        int firstEnd = Math.min(a.endChar(), b.endChar());

        // Находим начало сущности, которая идет в тексте второй
        int secondStart = Math.max(a.startChar(), b.startChar());

        // Если сущности перекрываются или идут вплотную друг к другу (без слов между ними)
        if (firstEnd >= secondStart) {
            return 0;
        }

        // Вырезаем подстроку СТРОГО между границами двух сущностей
        String intermediateText = text.substring(firstEnd, secondStart).trim();

        if (intermediateText.isEmpty()) {
            return 0;
        }

        // Расщепляем по любым пробельным символам (пробелы, табы, переносы строк)
        String[] words = intermediateText.split("\\s+");
        return words.length;
    }

}
