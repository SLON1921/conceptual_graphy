package ru.conceptgraph.modules.analytics.service;

import java.util.ArrayList;
import java.util.List;
public class PerformanceProfiler {

    private final List<ProfilerPoint> executionLog = new ArrayList<>();
    private final RegressionMath regressionMath = new RegressionMath();

    /**
     * Фиксирует результат единичного прогона системы.
     *
     * @param fileSizeKb Размер обработанного пакета данных в килобайтах.
     * @param durationMs Затраченное время в миллисекундах.
     */
    public void logResult(double fileSizeKb, long durationMs) {
        executionLog.add(new ProfilerPoint(fileSizeKb, durationMs));
    }

    public List<ProfilerPoint> getExecutionLog() {
        return new ArrayList<>(executionLog);
    }

    public void clearLogs() {
        executionLog.clear();
    }

    public String generateRegressionReport() {
        if (executionLog.size() < 2) {
            return "Недостаточно данных для генерации аналитического отчета. Требуется минимум 2 замера.";
        }

        double[] coefficients = regressionMath.calculateLinearRegression(executionLog);

        StringBuilder report = new StringBuilder();
        report.append("АНАЛИТИЧЕСКИЙ РАССЧЁТ РЕГРЕССИИ ПРОИЗВОДИТЕЛЬНОСТИ\n");
        report.append(String.format("Всего замеров: %d\n", executionLog.size()));
        report.append(regressionMath.getRegressionEquationString(coefficients)).append("\n");

        double testSizeKb = 50*1024.0;
        double predictedTimeMs = coefficients[0] * testSizeKb + coefficients[1];
        report.append(String.format("Прогноз времени для пакета данных 50 Мб: %.2f сек (%.0f мс)\n", predictedTimeMs / 1000.0, predictedTimeMs ));
        return report.toString();
    }
}
