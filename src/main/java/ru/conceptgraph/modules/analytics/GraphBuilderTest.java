package ru.conceptgraph.modules.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.conceptgraph.modules.analytics.service.GraphBuilder;
import ru.conceptgraph.modules.analytics.service.GraphBuilder.OccurrenceInfo;
import ru.conceptgraph.shared.Edge;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Тестирование математического конструктора графов (GraphBuilder)")
public class GraphBuilderTest {

    private GraphBuilder graphBuilder;
    private final Long docId = 1L;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
    }

    @Test
    @DisplayName("Должен возвращать пустой список, если вхождений меньше двух")
    void shouldReturnEmptyListWhenNotEnoughOccurrences() {
        List<OccurrenceInfo> emptyList = Collections.emptyList();
        List<OccurrenceInfo> singleElementList = List.of(new OccurrenceInfo(101L, 0));

        List<Edge> resultEmpty = graphBuilder.buildGraph(docId, emptyList, 5, Collections.emptySet(), 1.5);
        List<Edge> resultSingle = graphBuilder.buildGraph(docId, singleElementList, 5, Collections.emptySet(), 1.5);

        assertTrue(resultEmpty.isEmpty(), "Граф должен быть пустым для 0 вхождений");
        assertTrue(resultSingle.isEmpty(), "Граф должен быть пустым для 1 вхождения");
    }

    @Test
    @DisplayName("Не должен создавать ребро (петлю) для вхождений одного и того же понятия")
    void shouldNotCreateEdgesForSameConceptId() {
        // Два вхождения одного понятия (ID: 101) на расстоянии 12 символов (~2 слова)
        List<OccurrenceInfo> occurrences = List.of(
                new OccurrenceInfo(101L, 0),
                new OccurrenceInfo(101L, 12)
        );

        List<Edge> edges = graphBuilder.buildGraph(docId, occurrences, 5, Collections.emptySet(), 1.5);

        assertTrue(edges.isEmpty(), "Ребро-петля между одинаковыми Concept ID не должно создаваться");
    }

    @Test
    @DisplayName("Должен успешно создавать ребро, если понятия находятся в пределах порога N слов")
    void shouldCreateEdgeWithinThreshold() {
        // Понятия 101 и 102 на расстоянии 6 символов (~1 слово)
        List<OccurrenceInfo> occurrences = List.of(
                new OccurrenceInfo(101L, 0),
                new OccurrenceInfo(102L, 6)
        );

        // Порог N = 3 слова
        List<Edge> edges = graphBuilder.buildGraph(docId, occurrences, 3, Collections.emptySet(), 1.5);

        assertEquals(1, edges.size(), "Должно быть создано ровно одно ребро");
        Edge edge = edges.get(0);

        // Проверяем правильность связывания ID (комбинация minId и maxId в реализации)
        assertEquals(101L, edge.sourceConceptId());
        assertEquals(102L, edge.targetConceptId());
        assertTrue(edge.weight() > 0, "Вес ребра должен быть положительным числом");
    }

    @Test
    @DisplayName("Не должен создавать ребро, если понятия дальше порога N слов")
    void shouldNotCreateEdgeOutsideThreshold() {
        // Понятия 101 и 102 на расстоянии 60 символов (~10 слов)
        List<OccurrenceInfo> occurrences = List.of(
                new OccurrenceInfo(101L, 0),
                new OccurrenceInfo(102L, 60)
        );

        // Устанавливаем жесткий порог N = 2 слова
        List<Edge> edges = graphBuilder.buildGraph(docId, occurrences, 2, Collections.emptySet(), 1.5);

        assertTrue(edges.isEmpty(), "Ребро не должно создаваться, так как расстояние превышает N слов");
    }

    @Test
    @DisplayName("Должен динамически усиливать вес ребра, если оба понятия есть в предметном указателе")
    void shouldBoostWeightWhenBothConceptsAreInIndex() {
        List<OccurrenceInfo> occurrences = List.of(
                new OccurrenceInfo(101L, 0),
                new OccurrenceInfo(102L, 12)
        );

        // Сценарий 1: Без предметного указателя
        List<Edge> normalEdges = graphBuilder.buildGraph(docId, occurrences, 5, Collections.emptySet(), 2.0);
        double normalWeight = normalEdges.get(0).weight();

        // Сценарий 2: Оба понятия внесены в Предметный Указатель
        Set<Long> indexConceptIds = Set.of(101L, 102L);
        List<Edge> boostedEdges = graphBuilder.buildGraph(docId, occurrences, 5, indexConceptIds, 2.0);
        double boostedWeight = boostedEdges.get(0).weight();

        // Проверяем математическое условие: вес должен увеличиться ровно в boostFactor (2.0) раза
        assertEquals(normalWeight * 2.0, boostedWeight, 0.0001,
                "Вес ребра должен быть динамически усилен коэффициентом boostFactor");
    }

    @Test
    @DisplayName("Не должен усиливать вес, если только одно понятие есть в предметном указателе")
    void shouldNotBoostWeightIfOnlyOneConceptInIndex() {
        List<OccurrenceInfo> occurrences = List.of(
                new OccurrenceInfo(101L, 0),
                new OccurrenceInfo(102L, 12)
        );

        // В указателе есть только 101L, а 102L отсутствует
        Set<Long> partialIndex = Set.of(101L);

        List<Edge> normalEdges = graphBuilder.buildGraph(docId, occurrences, 5, Collections.emptySet(), 2.0);
        List<Edge> partialEdges = graphBuilder.buildGraph(docId, occurrences, 5, partialIndex, 2.0);

        assertEquals(normalEdges.get(0).weight(), partialEdges.get(0).weight(), 0.0001,
                "Вес не должен меняться, если в предметном указателе находится только одна вершина из двух");
    }
}
