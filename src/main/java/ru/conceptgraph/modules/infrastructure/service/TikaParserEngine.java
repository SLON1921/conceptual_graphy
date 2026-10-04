package ru.conceptgraph.modules.infrastructure.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import ru.conceptgraph.shared.Document;
import ru.conceptgraph.modules.aiandnlp.service.FileParser;
import java.io.File;


import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TikaParserEngine implements FileParser {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "html", "rtf", "txt");

    public List<Document> parseDirectory(String rootDirPath) throws IOException {
        List<Document> parsedDocuments = new ArrayList<>();
        Path rootPath = Paths.get(rootDirPath);

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Указанный путь не существует или не является директорией: " + rootDirPath);
        }

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                String extension = getFileExtension(fileName).toLowerCase();

                if (ALLOWED_EXTENSIONS.contains(extension)) {
                    try {
                        String rawText = parseFile(file);
                        parsedDocuments.add(new Document(null, fileName, file.toAbsolutePath().toString(), rawText));
                    } catch (Exception e) {
                        System.err.printf("[ОШИБКА] Не удалось распарсить файл %s: %s%n", fileName, e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.printf("[КРИТИЧЕСКАЯ ОШИБКА] Нет доступа к файлу %s: %s%n", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return parsedDocuments;
    }

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

    private String isolateMainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String regex = "(?i)(?<=\\n|^)(список\\s+литературы|библиографический\\s+список|предметный\\s+указатель|индекс)(\\s*\\n|\\s*\\r\\n|\\s*$)";
        String[] parts = text.split(regex, 2);
        return parts[0].trim();
    }

    private String getFileExtension(String fileName) {
        int lastIndexOf = fileName.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return fileName.substring(lastIndexOf + 1);
    }
    @Override
    public String extractText(File file) {
        try {
            return parseFile(file.toPath());
        } catch (Exception e) {
            System.err.printf("[ОШИБКА] Ошибка извлечения текста из %s: %s%n", file.getName(), e.getMessage());
            return "";
        }
    }

}
