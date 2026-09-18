package ru.conceptgraph;

import ru.conceptgraph.modules.aiandnlp.service.FileParser;
import ru.conceptgraph.modules.aiandnlp.service.FolderScanner;
import ru.conceptgraph.modules.aiandnlp.service.OpenNlpEngine;
import ru.conceptgraph.shared.Document;
import ru.conceptgraph.shared.ConceptEntity;

import java.io.File;
import java.util.List;

public class MainApplication {
    public static void main(String[] args) {
        System.out.println("    тестируем лингвис движок    ");

        OpenNlpEngine nlpEngine = new OpenNlpEngine();

        // заглушка для ромки
        FileParser temporaryParser = file ->
                "ромка лох  ромка лох  ромка лох  ромка лох  " +
                        "ромка лох ромка лох ромка лох ромка лох  " +
                        "ромка лох ромка лох ромка лох ромка лох ромка лох" +
                        "ромка лох ромка лох ромка лох ромка лох ромка лох ромка лох" +
                        "ромка лох ромка лох ромка лох ромка лох ромка лох ромка лох ромка лох";

        //сканер
        FolderScanner scanner = new FolderScanner(temporaryParser);

        String testPath = "C:\\test_docs";
        File targetDir = new File(testPath);

        List<Document> documents = scanner.scanAndParse(targetDir);

        for (Document doc : documents) {
            System.out.println("\n Анализируем документ: " + doc.name());

            // запуск извлечение существительных и позиций
            List<ConceptEntity> concepts = nlpEngine.extractNouns(doc.rawText());

            System.out.println(" найдено существительных: " + concepts.size());
            for (ConceptEntity concept : concepts) {
                System.out.printf("  * [%s] (Позиция: %d -> %d)\n",
                        concept.value(), concept.startChar(), concept.endChar());
            }
        }
    }
}
