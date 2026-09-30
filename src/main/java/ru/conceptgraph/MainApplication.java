package ru.conceptgraph;

import ru.conceptgraph.modules.aiandnlp.service.FileParser;
import ru.conceptgraph.modules.aiandnlp.service.FolderScanner;
import ru.conceptgraph.modules.aiandnlp.service.OpenNlpEngine;
import ru.conceptgraph.modules.aiandnlp.service.AiGraphEngine;
import ru.conceptgraph.modules.infrastructure.service.TikaParserEngine;
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

// Подключаем парсер ромки вместо заглушки
        TikaParserEngine tikaParser = new TikaParserEngine();

// Передаем его в сканер папок
        FolderScanner scanner = new FolderScanner(tikaParser);


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
            // 2. Прогоняем слова через Ollama для чистки омонимов и дедупликации (Лаба 4)
            System.out.println("\n[Локальный ИИ]: Уточнение категорий и контекстный анализ:");
            for (ConceptEntity concept : rawConcepts) {
                // Отправляем запрос в Ollama по HTTP
                ConceptEntity resolved = aiEngine.resolveOmonymAndType(concept, doc.rawText());

                // Печатаем вообще все узлы, чтобы увидеть результат работы конвейера!
                System.out.printf("  • Узел [%s] | Исправленный тип от ИИ: %s | Координаты: %d -> %d\n",
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
