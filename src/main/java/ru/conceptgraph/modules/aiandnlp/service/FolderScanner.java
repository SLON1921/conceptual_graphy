package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.shared.Document;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FolderScanner {

    private final FileParser fileParser;
    private long idCounter = 1;

    // теперь сканер требует на вход любой парсер, умеющий читать текст
    public FolderScanner(FileParser fileParser) {
        this.fileParser = fileParser;
    }


//      рекурсивный метод для обхода папки и создания объектов Document

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
                    // Рекурсивный прыжок во вложенную папку
                    documents.addAll(scanAndParse(item));
                } else {
                    // Проверяем расширения файлов строго по ТЗ
                    String name = item.getName().toLowerCase();
                    if (name.endsWith(".pdf") || name.endsWith(".docx") ||
                            name.endsWith(".html") || name.endsWith(".rtf") || name.endsWith(".txt")) {

                        // Вызываем метод интерфейса (будущую магию Ромы)
                        String text = fileParser.extractText(item);

                        // Упаковываем данные в созданный тобой Record Document
                        Document doc = new Document(idCounter++, item.getName(), item.getAbsolutePath(), text);
                        documents.add(doc);
                    }
                }
            }
        }

        return documents;
    }
}
