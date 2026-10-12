package ru.conceptgraph.modules.aiandnlp.service;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.util.Span;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.EntityType;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class OpenNlpEngine {

    private POSTaggerME posTagger;

    public OpenNlpEngine() {
        // Загрузка модели через стандартный механизм ресурсов Maven
        try (InputStream modelStream = getClass().getResourceAsStream("/opennlp-models/ru-pos.bin")) {
            if (modelStream == null) {
                throw new java.io.FileNotFoundException("Файл ru-pos.bin не найден в resources/opennlp-models/");
            }
            POSModel model = new POSModel(modelStream);
            this.posTagger = new POSTaggerME(model);
            System.out.println("[Успех]: Лингвистическая модель ru-pos.bin успешно загружена из ресурсов");
        } catch (Exception e) {
            System.err.println("[ ошибка NLP]: Не удалось инициализировать модель: " + e.getMessage());
        }
    }

    /**
     * Метод принимает текст документа, разбивает, находит существительные и вычисляет их позиции.
     */
    public List<ConceptEntity> extractNouns(String rawText) {
        List<ConceptEntity> concepts = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return concepts;
        }

        // Отсекаем список литературы и предметный указатель
        String cleanedText = cutOffUnwantedSections(rawText);

        // Используем встроенный токенизатор НЛП для разбиения текста на слова
        SimpleTokenizer tokenizer = SimpleTokenizer.INSTANCE;

        // Получаем спаны — они сразу содержат и само слово и его первый/последний символы в тексте
        Span[] tokenSpans = tokenizer.tokenizePos(cleanedText);

        // Переводим спаны в массив обычных строк-слов для ПОСтеггера
        String[] tokens = Span.spansToStrings(tokenSpans, cleanedText);

        // Если модель загружена — определяем части речи
        if (posTagger != null && tokens.length > 0) {
            String[] tags = posTagger.tag(tokens);

            for (int i = 0; i < tokens.length; i++) {
                String word = tokens[i];
                String tag = tags[i];

                // В корпусах Universal Dependencies существительные обозначаются тегом "NOUN" или "PROPN"
                if ("NOUN".equalsIgnoreCase(tag) || "PROPN".equalsIgnoreCase(tag)) {
                    if (word.length() > 2) {
                        int startChar = tokenSpans[i].getStart();
                        int endChar = tokenSpans[i].getEnd();

                        boolean sentenceStart = isSentenceStart(cleanedText, startChar);

                        // Склеивание слов через дефис («дата-центр»)
                        if (i < tokens.length - 2 && "-".equals(tokens[i + 1])) {
                            String nextTag = tags[i + 2];
                            if ("NOUN".equalsIgnoreCase(nextTag) || "PROPN".equalsIgnoreCase(nextTag)) {
                                word = word + "-" + tokens[i + 2];
                                endChar = tokenSpans[i + 2].getEnd();
                                i += 2; // Пропускаем дефис и вторую часть слова
                            }
                        }

                        // Слово в начале предложения пишется с большой буквы по правилам,
                        // поэтому обычное существительное там приводим к нижнему регистру
                        String value = word;
                        if (sentenceStart && "NOUN".equalsIgnoreCase(tag)
                                && !RussianMorphology.isAbbreviation(word)) {
                            value = word.toLowerCase();
                        }

                        // Падеж не трогаем: форму для узла выбирает конвейер по всем вхождениям,
                        // а группирует их по RussianMorphology.stemKey
                        EntityType type = determineEntityType(value, sentenceStart);

                        ConceptEntity concept = new ConceptEntity(value, type, startChar, endChar);

                        // Добавляем в список
                        concepts.add(concept);
                    }
                }
            }
        } else {
            // Резервный вариант (если бинарник модели на ПК не инициализировался)
            for (Span span : tokenSpans) {
                String word = cleanedText.substring(span.getStart(), span.getEnd());
                if (word.length() > 4 && Character.isLetter(word.charAt(0))) {
                    EntityType type = determineEntityType(word, false);
                    concepts.add(new ConceptEntity(word, type, span.getStart(), span.getEnd()));
                }
            }
        }

        return concepts;
    }

    /** Предел длины контекста для Ollama, чтобы не упираться в окно модели на длинных абзацах */
    private static final int MAX_SENTENCE_LENGTH = 400;

    /**
     * Возвращает предложение, в котором стоит слово с позициями [startChar, endChar).
     * Границы предложения: точка, ! ? или перевод строки. Длинные куски обрезаются вокруг слова.
     * Нужен, чтобы отправлять в Ollama контекст слова, а не весь документ.
     */
    public static String sentenceAround(String text, int startChar, int endChar) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, Math.min(startChar, text.length()));
        int end = Math.max(start, Math.min(endChar, text.length()));

        int from = sentenceStartOf(text, start);
        int to = end;
        while (to < text.length() && !isSentenceBoundary(text.charAt(to))) {
            to++;
        }
        if (to < text.length()) {
            to++; // захватываем сам знак конца предложения
        }

        if (to - from > MAX_SENTENCE_LENGTH) {
            int half = MAX_SENTENCE_LENGTH / 2;
            from = Math.max(from, start - half);
            to = Math.min(to, end + half);
        }
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }

    /** Позиция начала предложения, в котором стоит символ position */
    public static int sentenceStartOf(String text, int position) {
        int from = Math.max(0, Math.min(position, text.length()));
        while (from > 0 && !isSentenceBoundary(text.charAt(from - 1))) {
            from--;
        }
        return from;
    }

    private static boolean isSentenceBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n' || c == '\r';
    }

    /** Первое слово текста, строки или слово после . ! ? (пробелы и кавычки перед словом пропускаем) */
    private static boolean isSentenceStart(String text, int startChar) {
        int i = startChar - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t'
                || text.charAt(i) == '"' || text.charAt(i) == '«' || text.charAt(i) == '(')) {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char c = text.charAt(i);
        return c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' || c == '\r' || c == ':';
    }

    /**
     * Тип понятия без Ollama: словари из resources/dictionaries, потом правила по написанию.
     * Людей по именам находит PersonExtractor, здесь PERSON только для слов-ролей («профессор», «автор»).
     */
    static EntityType determineEntityType(String word, boolean sentenceStart) {
        EntityType fromDictionary = EntityDictionaries.lookup(word);
        if (fromDictionary != null) {
            return fromDictionary;
        }
        // Латиница в русском тексте — почти всегда технологии и продукты: Java, Python, TensorFlow, Git
        if (RussianMorphology.isLatin(word)) {
            return EntityType.TECHNOLOGY;
        }
        boolean capitalized = Character.isUpperCase(word.charAt(0));
        if (capitalized && !sentenceStart && EntityDictionaries.looksLikeToponym(word)) {
            return EntityType.GEOGRAPHY;
        }
        // Остальное (организации, предметы, понятия) — PRODUCT: другого типа в схеме БД пока нет
        return EntityType.PRODUCT;
    }

    /**
     * Логика отсечения лишних разделов.
     */
    private String cutOffUnwantedSections(String text) {
        String lower = text.toLowerCase();
        int litIndex = lower.indexOf("список литературы");
        int indexIndex = lower.indexOf("предметный указатель");

        int cutIndex = -1;
        if (litIndex != -1 && indexIndex != -1) {
            cutIndex = Math.min(litIndex, indexIndex);
        } else if (litIndex != -1) {
            cutIndex = litIndex;
        } else if (indexIndex != -1) {
            cutIndex = indexIndex;
        }

        if (cutIndex != -1) {
            return text.substring(0, cutIndex);
        }
        return text;
    }
}
