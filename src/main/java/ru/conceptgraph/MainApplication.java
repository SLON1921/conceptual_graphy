package ru.conceptgraph;

import ru.conceptgraph.core.config.AppConfig;
import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.FolderScanner;
import ru.conceptgraph.modules.aiandnlp.service.OpenNlpEngine;
import ru.conceptgraph.modules.aiandnlp.service.AiGraphEngine;
import ru.conceptgraph.modules.infrastructure.service.TikaParserEngine;
import ru.conceptgraph.shared.Document;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainApplication {

    /** Папка по умолчанию, если путь не передан аргументом запуска */
    private static final String DEFAULT_INPUT_DIR = "data/input";

    public static void main(String[] args) {
        // Кастомный перехватчик(если не обернуть в try-catch, то ошибка передастся сюда)
        Thread.setDefaultUncaughtExceptionHandler(new AppExceptionHandler());
        AppExceptionHandler exceptionHandler = new AppExceptionHandler();

        // Настройки БД из application.properties: без этого пул соединений не стартует
        AppConfig.loadProperties();

        System.out.println("   КОНСОЛЬНЫЙ ЯДРОВОЙ КОНВЕЙЕР CONCEPTER-GRAPH   ");
        System.out.println("                                                    ");

        // инициализируем синтаксический движок и ИИ-движок (Ollama API) мишанька пупс постарался
        OpenNlpEngine nlpEngine = new OpenNlpEngine();
        AiGraphEngine aiEngine = new AiGraphEngine();
        ConceptGraphPipeline pipeline = new ConceptGraphPipeline(
                nlpEngine, aiEngine, exceptionHandler, line -> System.out.println(" " + line));

        // Подключаем парсер ромки вместо заглушки и передаем его в сканер папок
        TikaParserEngine tikaParser = new TikaParserEngine();
        FolderScanner scanner = new FolderScanner(tikaParser);

        // Путь к папке берём из первого аргумента: java ... MainApplication "C:\docs"
        String inputPath = args.length > 0 ? args[0] : DEFAULT_INPUT_DIR;
        File targetDir = new File(inputPath);

        System.out.println("[Система]: Начинается сканирование директории: " + targetDir.getAbsolutePath());
        List<Document> documents = scanner.scanAndParse(targetDir);
        System.out.println("[Система]: Успешно найдено и обработано документов: " + documents.size());

        // анализируем по всем документам
        for (Document doc : documents) {
            System.out.println("\n-------------------------------------------------");
            System.out.println(" Анализ файла: " + doc.name());
            System.out.println(" (Путь): " + doc.path());

            // OpenNLP -> Ollama (по предложению слова) -> БД -> рёбра графа
            ConceptGraphPipeline.Result result = pipeline.process(
                    doc.name(), doc.path(), new File(doc.path()).length(), doc.rawText());

            System.out.println("\n[Локальный ИИ]: Уточнённые категории понятий:");
            Map<Long, String> namesById = new HashMap<>();
            for (ConceptEntity node : result.nodes()) {
                namesById.put(node.id(), node.value());
                System.out.printf("  • Узел [%s] (ID: %d) | Тип: %s | Первое вхождение: %d -> %d%n",
                        node.value(), node.id(), node.type(), node.startChar(), node.endChar());
            }

            System.out.println("\n [ИИ]: Сформированные ребра графа (Edges):");
            for (Edge edge : result.edges()) {
                System.out.printf(" * Связь: [%s] - [%s] | Вес (Важность): %.2f%n",
                        namesById.getOrDefault(edge.sourceConceptId(), "Узел_" + edge.sourceConceptId()),
                        namesById.getOrDefault(edge.targetConceptId(), "Узел_" + edge.targetConceptId()),
                        edge.weight());
            }
        }

        System.out.println("\n                                               ");
        System.out.println("       КОНВЕЙЕР УСПЕШНО ЗАВЕРШИЛ РАБОТУ          ");
        System.out.println("                                                 ");
    }
}
