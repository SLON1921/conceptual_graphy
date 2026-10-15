package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.infrastructure.service.TikaParserEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Анализ файлов без окна: текст из файлов, потом понятия, типы, БД и связи.
 * Здесь нет JavaFX: что показать на экране, решает окно через Progress.
 */
public class AnalysisService {

    /** Что окно хочет узнавать по ходу анализа. Методы вызываются из фонового потока. */
    public interface Progress {
        /** Из файла извлечён текст: done из total */
        void textExtracted(int done, int total);

        /** Текст готов, начинается поиск понятий и связей */
        void searchStarted();

        /** Строка для журнала */
        void log(String line);
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Попросить остановиться: анализ прервётся между файлами или после поиска понятий */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Запускать в фоновом потоке: может идти долго.
     *
     * @return результат или null, если анализ отменили
     */
    public ConceptGraphPipeline.Result analyze(List<File> files, boolean useWikipedia, Progress progress) {
        cancelled.set(false);

        // 1. Текст из файлов; между файлами можно отменить
        List<ConceptGraphPipeline.DocumentInput> documents = new ArrayList<>();
        TikaParserEngine parser = new TikaParserEngine();
        for (int i = 0; i < files.size(); i++) {
            if (cancelled.get()) {
                return null;
            }
            File file = files.get(i);
            String text = parser.extractText(file);
            documents.add(new ConceptGraphPipeline.DocumentInput(
                    file.getName(), file.getAbsolutePath(), file.length(), text));
            progress.textExtracted(i + 1, files.size());
        }
        progress.log("Текст извлечён: " + documents.size() + " док.");

        // 2. Понятия, типы, БД и связи
        progress.searchStarted();
        ConceptGraphPipeline pipeline = new ConceptGraphPipeline(
                new OpenNlpEngine(),
                new AppExceptionHandler(),
                progress::log
        ).withMeaningSource(useWikipedia ? new WikipediaMeaningSource() : null);
        ConceptGraphPipeline.Result result = pipeline.processAll(documents);
        return cancelled.get() ? null : result;
    }

    /** Те же файлы, что берёт FolderScanner: все поддерживаемые, включая вложенные папки */
    public static List<File> supportedFiles(File folder) {
        List<File> out = new ArrayList<>();
        collectSupported(folder, out);
        return out;
    }

    private static void collectSupported(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectSupported(child, out);
            } else if (SupportedFormats.isSupported(child)) {
                out.add(child);
            }
        }
    }
}
