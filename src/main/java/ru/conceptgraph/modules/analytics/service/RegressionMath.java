package ru.conceptgraph.modules.analytics.service;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import java.util.List;

/**
 * Класс для расчета уравнения регрессии производительности методом наименьших квадратов.
 */

public class RegressionMath {

    /**
     * Вычисляет коэффициенты линейной регрессии: y = slope * x + intercept
     * где x - размер данных (Кб), y - время обработки (мс).
     *
     * @param points Список экспериментальных точек замеров.
     * @return Массив из двух элементов: [slope (угловой коэффициент), intercept (свободный член)]
     */
    public double[] calculateLinearRegression(List<ProfilerPoint> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("Для расчета регрессии необходимо как минимум 2 точки замеров.");
        }

        SimpleRegression regression = new SimpleRegression(true);

        for(ProfilerPoint point :points) {
            regression.addData(point.dataSizeKb(), point.timeMs());
        }
        return new double[]{regression.getSlope(), regression.getIntercept()};
    }

    public String getRegressionEquationString(double[] coefficients) {
        return String.format("Уравнение регрессии скорости системы: f(x) = %.4f * x + %.2f (где x — размер файла в Кб, f(x) — время в мс)", coefficients[0], coefficients[1]);
    }

}
