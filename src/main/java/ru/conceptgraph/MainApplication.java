package ru.conceptgraph;

import ru.conceptgraph.modules.aiandnlp.service.service.FileParser;
import ru.conceptgraph.modules.aiandnlp.service.service.FolderScanner;
import ru.conceptgraph.modules.aiandnlp.service.service.OpenNlpEngine;
import ru.conceptgraph.modules.aiandnlp.service.service.AiGraphEngine;
import ru.conceptgraph.shared.Document;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.io.File;
import java.util.List;

public class MainApplication {
    public static void main(String[] args) {
        System.out.println("   КОНСОЛЬНЫЙ ЯДРОВОЙ КОНВЕЙЕР CONCEPTER-GRAPH   ");
        System.out.println("                                                    ");

        // инициализируем синтаксический движок и ИИ-движок (Ollama API) мишанька пупс постарался
        OpenNlpEngine nlpEngine = new OpenNlpEngine();
        AiGraphEngine aiEngine = new AiGraphEngine();

        // заглушка ромки
        FileParser temporaryParser = file ->
                "Жена застаёт мужа с любовницей. " +
                        "Муж в панике кричит:— Дорогая, не верь своим глазам! " +
                        "Верь моему сердцу, оно бьётся только для тебя! Жена отвечает:" +
                        "Да, но твой пусицыю чувствует другая баба!";

        // сканер папок
        FolderScanner scanner = new FolderScanner(temporaryParser);

        String testPath = "C:\\test_docs";
        File targetDir = new File(testPath);

        System.out.println("[Система]: Начинается сканирование директории: " + testPath);
        List<Document> documents = scanner.scanAndParse(targetDir);
        System.out.println("[Система]: Успешно найдено и обработано документов: " + documents.size());

        // анализируем по всем документам
        for (Document doc : documents) {
            System.out.println("\n-------------------------------------------------");
            System.out.println(" Анализ файла: " + doc.name());
            System.out.println(" (Путь): " + doc.path());

            // извлечение базовых существительных через OpenNLP (тож Лаба 3)
            List<ConceptEntity> rawConcepts = nlpEngine.extractNouns(doc.rawText());
            System.out.println(" Извлечено понятий (OpenNLP Concepts): " + rawConcepts.size());

            // прогоняем слова через Ollama для чистки омонимов и дедупликации (Лаба 4)
            System.out.println("\n[Локальный ИИ]: Уточнение категорий и контекстный анализ:");
            for (ConceptEntity concept : rawConcepts) {
                ConceptEntity resolved = aiEngine.resolveOmonymAndType(concept, doc.rawText());
                System.out.printf("  * Узел [%s] | Тип по ТЗ: %s | Смещение: %d -> %d\n",
                        resolved.value(), resolved.type(), resolved.startChar(), resolved.endChar());
            }

            // строим связи графа (Edges) между узлами
            List<Edge> edges = aiEngine.buildSemanticConnections(rawConcepts, doc.rawText());
            System.out.println("\n [ИИ]: Сформированные ребра графа (Edges):");
            for (Edge edge : edges) {
                System.out.printf("  * Связь: Узел %d - Узел %d | Вес (Важность): %.2f\n",
                        edge.sourceConceptId(), edge.targetConceptId(), edge.weight());
            }
        }
        System.out.println("\n                                               ");
        System.out.println("       КОНВЕЙЕР УСПЕШНО ЗАВЕРШИЛ РАБОТУ          ");
        System.out.println("                                                 ");
    }
}
