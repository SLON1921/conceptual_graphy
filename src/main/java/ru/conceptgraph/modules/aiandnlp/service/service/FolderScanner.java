package ru.conceptgraph.modules.aiandnlp.service.service;

import ru.conceptgraph.shared.Document;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class FolderScanner {

    private final FileParser fileParser;
    // безопасный айди для фронта
    private final AtomicLong idCounter = new AtomicLong(1);

    public FolderScanner(FileParser fileParser) {
        this.fileParser = fileParser;
    }

//рекурсия для обхода паппки и создания объектов
    public List<Document> scanAndParse(File rootDir) {
        List<Document> documents = new ArrayList<>();

        if (rootDir == null || !rootDir.exists()) {
            System.err.println("Ошибка: Указанная папка не существует!");
            return documents;
        }

        File[] filesAndDirs = rootDir.listFiles();
        if (filesAndDirs != null) {
            for (File item : filesAndDirs) {
                if (item.isDirectory()) {
                    // рекурсия импорт во вложенную папку
                    documents.addAll(scanAndParse(item));
                } else {
                    // проверка разширения по тз
                    String name = item.getName().toLowerCase();

                    // игнорируем скрытые файлы и временные файлы документов
                    if (item.isHidden() || name.startsWith("~$") || name.startsWith(".")) {
                        continue;
                    }

                    if (name.endsWith(".pdf") || name.endsWith(".docx") ||
                            name.endsWith(".html") || name.endsWith(".rtf") || name.endsWith(".txt")) {

                        // вызываем метод интерфейса
                        String text = fileParser.extractText(item);

                        Document doc = new Document(
                                idCounter.getAndIncrement(),
                                item.getName(),
                                item.getAbsolutePath(),
                                text
                        );
                        documents.add(doc);
                    }
                }
            }
        }

        return documents;
    }
}
