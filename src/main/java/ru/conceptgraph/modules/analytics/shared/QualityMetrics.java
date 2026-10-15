package ru.conceptgraph.modules.analytics.shared;

public record QualityMetrics(double precision, double recall, double f1Score) {
    @Override
    public String toString() {
        return String.format("Метрики качества:\nPrecision (Точность): %.2f%%\nRecall (Полнота): %.2f%%\nF1-Score: %.2f%%",
                precision * 100, recall * 100, f1Score * 100);

    }
}
