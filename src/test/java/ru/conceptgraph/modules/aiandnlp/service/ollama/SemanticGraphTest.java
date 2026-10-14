package ru.conceptgraph.modules.aiandnlp.service.ollama;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ru.conceptgraph.shared.Edge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticGraphTest {

    @Test
    void chunksFollowParagraphsAndCoverWholeText() {
        String text = "раз два три\nчетыре пять\nшесть семь восемь девять\n";
        List<TextChunker.Chunk> chunks = TextChunker.split(text, 5);
        assertEquals(2, chunks.size());
        assertEquals("раз два три\nчетыре пять\n", text.substring(chunks.get(0).start(), chunks.get(0).end()));
        assertEquals(text.length(), chunks.get(1).end());
    }

    @Test
    void hugeParagraphIsCutByWords() {
        String text = "a b c d e f g h i j";
        List<TextChunker.Chunk> chunks = TextChunker.split(text, 4);
        assertEquals(3, chunks.size());
        for (TextChunker.Chunk chunk : chunks) {
            assertTrue(TextChunker.countWords(text, chunk.start(), chunk.end()) <= 4);
        }
    }

    @Test
    void linksAreMatchedByNumberNameOrOtherCase() {
        Map<String, Long> concepts = new LinkedHashMap<>();
        concepts.put("Настенька", 1L);
        concepts.put("Петербурге", 2L);
        concepts.put("мечтатель", 3L);
        var answer = JsonParser.parseString("""
                {"summary": "...", "links": [
                  {"a": 1, "b": 3, "w": 0.9},
                  {"a": "Петербург", "b": "Москва", "w": 0.5},
                  {"a": 2, "b": 2, "w": 0.5},
                  {"a": 9, "b": 1, "w": 0.5},
                  {"a": "мечтателя", "b": "Петербурге", "w": 7},
                  [1, 2, 0.3]
                ]}""").getAsJsonObject();
        Map<String, double[]> weights = new HashMap<>();
        int[] counts = SemanticGraphEngine.addLinks(answer, concepts, weights);
        assertEquals(6, counts[0]);
        assertEquals(3, counts[1]);
        assertEquals(0.9, weights.get("1:3")[0], 1e-9);
        assertEquals(1.0, weights.get("2:3")[0], 1e-9); // «мечтателя» = «мечтатель», вес обрезан до 1.0
        assertEquals(0.3, weights.get("1:2")[0], 1e-9); // короткая запись [a, b, w]
    }

    @Test
    void loopedAnswerCutByTokenLimitIsSalvaged() {
        Map<String, Long> concepts = new LinkedHashMap<>();
        concepts.put("Настенька", 1L);
        concepts.put("Петербурге", 2L);
        concepts.put("мечтатель", 3L);
        // модель зациклилась, ответ оборвался на лимите посреди связи
        var answer = SemanticGraphEngine.salvage("""
                {"summary": "Мечтатель гуляет по \\"белым\\" ночам", "links": [[1, 3, 0.9], [2, 3, 0.6],
                [1, 3, 0.9], [2, 3, 0.6], [1, 3, 0.9], [2, 3""");
        assertEquals("Мечтатель гуляет по \"белым\" ночам", answer.get("summary").getAsString());
        Map<String, double[]> weights = new HashMap<>();
        int[] counts = SemanticGraphEngine.addLinks(answer, concepts, weights);
        assertEquals(5, counts[0]);
        assertEquals(2, counts[1]); // повторы одной пары не раздувают вес
        assertEquals(0.9, weights.get("1:3")[0], 1e-9);
        assertEquals(0.6, weights.get("2:3")[0], 1e-9);
    }

    @Test
    void combineCountsOverlapInBothDirections() {
        List<Edge> distance = List.of(new Edge(1L, 2L, 1.0), new Edge(2L, 3L, 0.4));
        List<Edge> meaning = List.of(new Edge(2L, 1L, 0.6), new Edge(1L, 3L, 0.8));
        GraphComparison.Result result = GraphComparison.combine(distance, meaning);
        assertEquals(1, result.both());
        assertEquals(1, result.onlyDistance());
        assertEquals(1, result.onlyMeaning());
        assertEquals(50.0, result.meaningAlsoInDistancePercent(), 1e-9);
        Edge both = result.combined().stream()
                .filter(e -> GraphComparison.LABEL_BOTH.equals(e.label())).findFirst().orElseThrow();
        assertEquals(0.8, both.weight(), 1e-9);
    }
}
