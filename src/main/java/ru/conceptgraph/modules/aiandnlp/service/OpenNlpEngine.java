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

                        // --- ИСПРАВЛЕНИЕ 1: Склеивание слов через дефис (Человек-паук) ---
                        if (i < tokens.length - 2 && "-".equals(tokens[i + 1])) {
                            String nextTag = tags[i + 2];
                            if ("NOUN".equalsIgnoreCase(nextTag) || "PROPN".equalsIgnoreCase(nextTag)) {
                                word = word + "-" + tokens[i + 2];
                                endChar = tokenSpans[i + 2].getEnd();
                                i += 2; // Пропускаем дефис и вторую часть слова
                            }
                        }

                        // --- ИСПРАВЛЕНИЕ 2: Приведение к базовой форме (Слияние падежей) ---
                        String normalizedWord = normalizeRussianNoun(word);

                        // --- ИСПРАВЛЕНИЕ 3: Динамическое определение типа сущности ---
                        EntityType type = determineEntityType(normalizedWord);

                        ConceptEntity concept = new ConceptEntity(normalizedWord, type, startChar, endChar);

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
                    String normalized = normalizeRussianNoun(word);
                    EntityType type = determineEntityType(normalized);
                    concepts.add(new ConceptEntity(normalized, type, span.getStart(), span.getEnd()));
                }
            }
        }

        return concepts;
    }

    /**
     * Эвристический метод для базовой нормализации окончаний русских существительных.
     * Помогает объединить "фильме" -> "фильм", "монстров" -> "монстр".
     */
    private String normalizeRussianNoun(String word) {
        if (word == null || word.length() <= 3) {
            return word;
        }

        String lower = word.toLowerCase();

        // Точечные правила для часто встречающихся слов контекста
        if (lower.endsWith("фильме") || lower.endsWith("фильма") || lower.endsWith("фильму")) {
            return "фильм";
        }
        if (lower.endsWith("монстров") || lower.endsWith("монстрам") || lower.endsWith("монстры")) {
            return "монстр";
        }
        if (lower.endsWith("друзья") || lower.endsWith("друзей")) {
            return "друзья";
        }

        // Универсальные эвристические правила отсечения падежных окончаний
        if (lower.endsWith("ами") || lower.endsWith("ями")) {
            return word.substring(0, word.length() - 3);
        }
        if (lower.endsWith("ов") || lower.endsWith("ей") || lower.endsWith("ам") || lower.endsWith("ях") || lower.endsWith("ах")) {
            return word.substring(0, word.length() - 2);
        }
        if (lower.endsWith("е") || lower.endsWith("а") || lower.endsWith("у") || lower.endsWith("ы") || lower.endsWith("и")) {
            return word.substring(0, word.length() - 1);
        }

        return word;
    }

    /**
     * Определяет тип сущности на основе её написания.
     */
    private EntityType determineEntityType(String word) {
        // Если слово начинается с заглавной буквы (например, Рома, Ваня) — это PERSON
        if (Character.isUpperCase(word.charAt(0))) {
            return EntityType.PERSON;
        }

        String lower = word.toLowerCase();
        if (lower.contains("человек-паук") || lower.contains("герой")) {
            return EntityType.PERSON;
        }

        // Во всех остальных случаях возвращаем стандартный тип
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
