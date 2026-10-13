package ru.conceptgraph.modules.aiandnlp.service;

import java.io.File;
import java.util.Locale;
import java.util.Set;

/**
 * Единый список форматов, которые берёт в работу сканер папок и показывает дерево в окне.
 * Раньше FolderScanner и окно держали каждый свой список, и они расходились.
 * Все эти форматы Apache Tika (парсер Ромы) умеет читать.
 */
public final class SupportedFormats {

    public static final Set<String> EXTENSIONS = Set.of(
            "txt", "pdf", "doc", "docx", "rtf", "html", "htm", "odt", "ppt", "pptx"
    );

    private SupportedFormats() {
    }

    public static boolean isSupported(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        // скрытые и временные файлы офиса не берём
        if (file.isHidden() || name.startsWith("~$") || name.startsWith(".")) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot != -1 && EXTENSIONS.contains(name.substring(dot + 1));
    }
}
