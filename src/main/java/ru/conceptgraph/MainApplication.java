package ru.conceptgraph;

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
                "Вот ваш текст ровно из пятидесяти слов об осенней " +
                        "природе: Осень приходит тихо. Жёлтые листья кружатся в воздухе и" +
                        " падают на холодную землю. Согревает редкий луч солнца, но воздух уже " +
                        "наполнен дождём и свежестью. Птицы летят на юг, спасаясь от холода. Природа " +
                        "отдыхает перед долгой зимой, укрываясь разноцветным ковром из листвы. Это прекрасное" +
                        " время для долгих прогулок и тихих размышлений.";

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
