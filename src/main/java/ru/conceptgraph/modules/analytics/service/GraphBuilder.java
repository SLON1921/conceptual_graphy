package ru.conceptgraph.modules.analytics.service;

import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.ConceptEntity; // Используется в логике сопоставления, если необходимо

import java.util.*;


public class GraphBuilder {

    // Вспомогательный класс для удобного представления вхождения понятия в коде расчетного движка
    public static record OccurrenceInfo(Long conceptId, int charPosition) {}

    /**
     * Построение списка ребер графа для конкретного документа.
     *
     * @param documentId         ID обрабатываемого документа
     * @param occurrences        Список всех вхождений понятий в данном документе
     * @param wordThresholdN     Пороговое значение расстояния в словах (N), в пределах которого создается связь
     * @param indexConceptIds    Набор ID понятий, которые были одновременно обнаружены в вырезанном «Предметном указателе»
     * @param boostFactor        Коэффициент динамического усиления веса (например, 1.5 или 2.0) для элементов из предметного указателя
     * @return Список построенных и взвешенных ребер графа
     */
    public List<Edge> buildGraph(Long documentId,
                                 List<OccurrenceInfo> occurrences,
                                 int wordThresholdN,
                                 Set<Long> indexConceptIds,
                                 double boostFactor) {

        if (occurrences == null || occurrences.size() < 2) {
            return Collections.emptyList();
        }

        // 1. Сортируем вхождения по их абсолютной символьной позиции в документе
        List<OccurrenceInfo> sortedOccurrences = new ArrayList<>(occurrences);
        sortedOccurrences.sort(Comparator.comparingInt(OccurrenceInfo::charPosition));

        // Карта для агрегации весов между парами вершин (чтобы не дублировать ребра A->B и B->A)
        // Ключ: уникальный идентификатор пары (комбинация меньшего и большего ID)
        Map<String, EdgeAccumulator> edgeMap = new HashMap<>();

        // 2. Алгоритм скользящего окна / попарного сравнения
        for (int i = 0; i < sortedOccurrences.size(); i++) {
            OccurrenceInfo occA = sortedOccurrences.get(i);

            for (int j = i + 1; j < sortedOccurrences.size(); j++) {
                OccurrenceInfo occB = sortedOccurrences.get(j);

                // Если это вхождения одного и того же понятия, ребро не создаем
                if (occA.conceptId().equals(occB.conceptId())) {
                    continue;
                }

                // Базовый вес ребра по формуле из ТЗ: distance(A, B) = |position(A) - position(B)|
                // Вычисляем абсолютное символьное расстояние
                int charDistance = Math.abs(occA.charPosition() - occB.charPosition());

                // Переводим символьное расстояние в примерное расстояние в словах.
                // Принимаем среднюю длину слова со средним пробелом за 6 символов (эмпирическая константа лингвистического анализа)
                double distanceInWords = charDistance / 6.0;

                // Проверяем пространственный порог N слов
                if (distanceInWords <= wordThresholdN) {

                    // Вычисляем базовый вес. Чем ближе слова друг к другу, тем выше должна быть "сила связи".
                    // Поскольку ТЗ просит использовать формулу расстояния, мы сохраняем дистанцию,
                    // но для GraphStream "сила" связи обычно инвертирована, либо мы аккумулируем чистый показатель.
                    // Реализуем математически корректную аккумуляцию базового веса:
                    double baseWeight = 1.0 / (distanceInWords + 1.0);

                    // Динамическое усиление: если ОБА понятия присутствуют в вырезанном предметном указателе
                    if (indexConceptIds != null &&
                            indexConceptIds.contains(occA.conceptId()) &&
                            indexConceptIds.contains(occB.conceptId())) {
                        baseWeight *= boostFactor; // Пропорционально увеличиваем вес ребра
                    }

                    // Формируем детерминированный ключ пары (меньший_id : больший_id)
                    long minId = Math.min(occA.conceptId(), occB.conceptId());
                    long maxId = Math.max(occA.conceptId(), occB.conceptId());
                    String pairKey = minId + "_" + maxId;

                    // Добавляем вес в карту
                    edgeMap.computeIfAbsent(pairKey, k -> new EdgeAccumulator(minId, maxId))
                            .addWeight(baseWeight);
                } else {
                    // Так как список отсортирован по позициям, если мы вышли за пределы порога по расстоянию,
                    // для текущего occA дальнейшие occB также будут слишком далеко. Из оптимизации — выходим во внутреннем цикле.
                    break;
                }
            }
        }

        // Convert the accumulated pairs into final shared Edge records
        List<Edge> finalEdges = new ArrayList<>();
        for (EdgeAccumulator acc : edgeMap.values()) {
            finalEdges.add(new Edge(acc.sourceId, acc.targetId, acc.totalWeight));
        }

        return finalEdges;
    }


    private static class EdgeAccumulator {
        final long sourceId;
        final long targetId;
        double totalWeight;

        EdgeAccumulator(long sourceId, long targetId) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.totalWeight = 0.0;
        }

        void addWeight(double weight) {
            this.totalWeight += weight;
        }
    }
}
