package ru.conceptgraph.modules.infrastructure.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import ru.conceptgraph.shared.Document;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Инфраструктурный движок для потокового парсинга файлов.
 * Отвечает за рекурсивный обход каталогов, извлечение текста через Apache Tika
 * и изоляцию основного содержимого от списков литературы и индексов.
 */
public class TikaParserEngine {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "html", "rtf", "txt");

    /**
     * Рекурсивно обходит целевую корневую директорию и парсит все поддерживаемые файлы.
     * Использует итеративный обход Java NIO.2 для предотвращения StackOverflowError.
     *
     * @param rootDirPath Строковый путь к корневой папке для сканирования
     * @return Список объектов Document (shared Java Record), содержащих извлеченный текст
     * @throws IOException При критических ошибках доступа к корневой директории
     */
    public List<Document> parseDirectory(String rootDirPath) throws IOException {
        List<Document> parsedDocuments = new ArrayList<>();
        Path rootPath = Paths.get(rootDirPath);

        // Проверка физического существования папки на жестком диске
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Указанный путь не существует или не является директорией: " + rootDirPath);
        }

        // Обхода дерева папок
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                String extension = getFileExtension(fileName).toLowerCase(); // Защита от разного регистра (.PDF)

                if (ALLOWED_EXTENSIONS.contains(extension)) {
                    try {
                        String rawText = parseFile(file);

                        parsedDocuments.add(new Document(null, fileName, file.toAbsolutePath().toString(), rawText));
                    } catch (Exception e) {
                        System.err.printf("[ОШИБКА] Не удалось распарсить файл %s: %s%n", fileName, e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE; // Переход к следующему файлу в папке
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.printf("[КРИТИЧЕСКАЯ ОШИБКА] Нет доступа к файлу %s: %s%n", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return parsedDocuments;
    }

    /**
     * Извлекает чистый текст из одиночного файла с помощью библиотеки Apache Tika.
     * Реализует паттерн "Декоратор" для буферизации ввода-вывода.
     *
     * @param filePath Путь к конкретному файлу на диске
     * @return Полный извлеченный текст документа без метаданных и разметки
     * @throws Exception При сбоях декодирования структуры файла парсером Tika
     */
    public String parseFile(Path filePath) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();

        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try (InputStream stream = new BufferedInputStream(Files.newInputStream(filePath))) {
            parser.parse(stream, handler, metadata, context);
            String fullText = handler.toString();

            return isolateMainText(fullText);
        }
    }

    /**
     * Выполняет изоляцию структуры текста согласно пункту 2.1 ТЗ.
     * Находит и отсекает разделы «Список литературы» и «Предметный указатель» (индекс).
     *
     * @param text Полный сырой текст из файла
     * @return Очищенный основной текст до стоп-разделов
     */
    private String isolateMainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        /*
         * ПОДРОБНЫЙ РАЗБОР РЕГУЛЯРНОГО ВЫРАЖЕНИЯ (REGEX) ДЛЯ ЗАЩИТЫ НА ЛАБОРАТОРНОЙ:
         *
         * 1. (?i) — Флаг РЕГИСТРОНЕЗАВИСИМОСТИ. Ищет заголовки в любом регистре: "Список Литературы", "СПИСОК ЛИТЕРАТУРЫ", "индекс".
         *
         * 2. (?<=\\n|^) — ПОЛОЖИТЕЛЬНАЯ РЕТРОСПЕКТИВНАЯ ПРОВЕРКА.
         *    Она жестко требует, чтобы перед ключевым словом шел символ новой строки (\n) или это было самое начало документа (^).
         *
         * 3. (список\\s+литературы|библиографический\\s+список|предметный\\s+указатель|индекс) — ГРУППА ПЕРЕЧИСЛЕНИЯ (ИЛИ).
         *
         * 4. (\\s*\\n|\\s*\\r\\n|\\s*$) — УСЛОВИЕ ПРАВОГО КОНТЕКСТА.
         *
         */
        String regex = "(?i)(?<=\\n|^)(список\\s+литературы|библиографический\\s+список|предметный\\s+указатель|индекс)(\\s*\\n|\\s*\\r\\n|\\s*$)";

        String[] parts = text.split(regex, 2);

        return parts[0].trim();
    }

    /**
     * Безопасно извлекает расширение файла из его строкового имени.
     *
     * @param fileName Имя файла (например, "data.docx")
     * @return Строка расширения ("docx") или пустая строка, если расширение отсутствует
     */
    private String getFileExtension(String fileName) {
        int lastIndexOf = fileName.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return ""; // У файла нет расширения
        }
        return fileName.substring(lastIndexOf + 1);
    }
}
