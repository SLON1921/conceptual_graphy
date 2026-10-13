package ru.conceptgraph.modules.analytics;

import org.junit.jupiter.api.Test;
import ru.conceptgraph.modules.analytics.service.QualityEvaluator;
import ru.conceptgraph.modules.analytics.shared.QualityMetrics;
import ru.conceptgraph.shared.Edge;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class QualityEvaluatorTest {

    @Test
    public void testMetricsCalculation() {
        QualityEvaluator evaluator = new QualityEvaluator();

        // Эталонный граф (3 ребра)
        List<Edge> goldStandard = List.of(
                new Edge(1L, 2L, 1.0),
                new Edge(2L, 3L, 1.0),
                new Edge(3L, 4L, 1.0)
        );

        // Граф системы (Нашел 2 правильных ребра и 1 ложное)
        List<Edge> systemEdges = List.of(
                new Edge(2L, 1L, 1.0), // Правильное (порядок инвертирован, но equals сработает)
                new Edge(2L, 3L, 1.0), // Правильное
                new Edge(4L, 5L, 1.0)  // Ложное (FP)
                // Ребро (3, 4) упущено (FN)
        );

        QualityMetrics metrics = evaluator.evaluate(systemEdges, goldStandard);

        // TP = 2 (связи 1-2 и 2-3)
        // Precision = TP / SystemSize = 2 / 3 = 0.666...
        // Recall = TP / GoldSize = 2 / 3 = 0.666...

        assertEquals(2.0 / 3.0, metrics.precision(), 0.001);
        assertEquals(2.0 / 3.0, metrics.recall(), 0.001);
        assertEquals(2.0 / 3.0, metrics.f1Score(), 0.001);
    }
}
