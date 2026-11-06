package ru.conceptgraph.modules.analytics;

import org.junit.jupiter.api.Test;
import ru.conceptgraph.modules.analytics.service.ProfilerPoint;
import ru.conceptgraph.modules.analytics.service.RegressionMath;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class RegressionMathTest {

    @Test
    public void testRegressionMathWithStaticPoints() {
        RegressionMath regressionMath = new RegressionMath();

        // Идеальные точки для прямой y = 2x + 10
        List<ProfilerPoint> testPoints = List.of(
                new ProfilerPoint(100.0, 210.0),  // 2*100 + 10 = 210
                new ProfilerPoint(200.0, 410.0),  // 2*200 + 10 = 410
                new ProfilerPoint(500.0, 1010.0)  // 2*500 + 10 = 1010
        );

        double[] coefficients = regressionMath.calculateLinearRegression(testPoints);

        // Проверяем slope (должен быть равен 2.0)
        assertEquals(2.0, coefficients[0], 0.0001);
        // Проверяем intercept (должен быть равен 10.0)
        assertEquals(10.0, coefficients[1], 0.0001);
    }
}
