package ru.conceptgraph.modules.aiandnlp.service.ollama;

import ru.conceptgraph.shared.Edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сравнивает и объединяет два графа на одних и тех же узлах:
 * граф Вани (по расстоянию в тексте) и смысловой граф Ollama.
 */
public final class GraphComparison {

    /** Подписи рёбер общего графа: откуда взялась связь (видны в карточке понятия) */
    public static final String LABEL_BOTH = "оба графа";
    public static final String LABEL_DISTANCE = "по расстоянию";
    public static final String LABEL_MEANING = "по смыслу";

    /**
     * @param combined      общий граф: вес = среднее двух весов (у отсутствующего ребра вес 0)
     * @param both          рёбер есть в обоих графах
     * @param onlyDistance  только у Вани
     * @param onlyMeaning   только в смысловом
     */
    public record Result(List<Edge> combined, int both, int onlyDistance, int onlyMeaning) {

        /** Какая доля смысловых связей нашлась и в графе по расстоянию, % */
        public double meaningAlsoInDistancePercent() {
            int meaning = both + onlyMeaning;
            return meaning == 0 ? 0 : 100.0 * both / meaning;
        }

        /** Сходство графов (Жаккар): общие рёбра от всех рёбер обоих графов, % */
        public double similarityPercent() {
            int all = both + onlyDistance + onlyMeaning;
            return all == 0 ? 0 : 100.0 * both / all;
        }
    }

    private GraphComparison() {
    }

    public static Result combine(List<Edge> distance, List<Edge> meaning) {
        Map<String, double[]> pairs = new LinkedHashMap<>(); // {вес по расстоянию, вес по смыслу}
        for (Edge e : distance) {
            pairs.computeIfAbsent(pairKey(e.sourceConceptId(), e.targetConceptId()), k -> new double[2])[0] += e.weight();
        }
        for (Edge e : meaning) {
            pairs.computeIfAbsent(pairKey(e.sourceConceptId(), e.targetConceptId()), k -> new double[2])[1] += e.weight();
        }

        List<Edge> combined = new ArrayList<>();
        int both = 0;
        int onlyDistance = 0;
        int onlyMeaning = 0;
        for (Map.Entry<String, double[]> entry : pairs.entrySet()) {
            double d = entry.getValue()[0];
            double m = entry.getValue()[1];
            String label;
            if (d > 0 && m > 0) {
                both++;
                label = LABEL_BOTH;
            } else if (d > 0) {
                onlyDistance++;
                label = LABEL_DISTANCE;
            } else {
                onlyMeaning++;
                label = LABEL_MEANING;
            }
            long[] ids = parsePairKey(entry.getKey());
            combined.add(new Edge(ids[0], ids[1], (d + m) / 2, label));
        }
        return new Result(combined, both, onlyDistance, onlyMeaning);
    }

    /** Ключ пары без учёта направления: A–B и B–A — одно ребро */
    static String pairKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    static long[] parsePairKey(String key) {
        int colon = key.indexOf(':');
        return new long[]{Long.parseLong(key.substring(0, colon)), Long.parseLong(key.substring(colon + 1))};
    }
}
