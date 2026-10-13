package ru.conceptgraph.modules.analytics.service;

import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.modules.analytics.shared.QualityMetrics;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;


public class QualityEvaluator {

    /**
     * Вычисляет Precision, Recall и F1-Score на основе сравнения
     * графа системы и золотого стандарта (эталона).
     *
     * @param systemEdges Ребра, сгенерированные программой
     * @param goldStandard Ребра, размеченные вручную (эталон)
     * @return Объект с рассчитанными метриками
     */

    public QualityMetrics evaluate(Collection<Edge> systemEdges, Collection<Edge> goldStandard) {
        if (goldStandard == null || goldStandard.isEmpty()) {
            return new QualityMetrics(0.0,0.0,0.0);
        }
        if (systemEdges == null || systemEdges.isEmpty()) {
            return new QualityMetrics(0.0,0.0,0.0);
        }

        Set<Edge> systemSet = new HashSet<>(systemEdges);
        Set<Edge> goldSet = new HashSet<>(goldStandard);

        long truePositives = systemSet.stream().filter(goldSet::contains).count();

        double precision = (double) truePositives / systemSet.size();
        double recall = (double) truePositives / goldSet.size();

        double f1Score = (precision+recall)>0
                ? 2*(precision*recall) / (precision+recall)
                :0.0;
        return new QualityMetrics(precision,recall,f1Score);
    }

}
