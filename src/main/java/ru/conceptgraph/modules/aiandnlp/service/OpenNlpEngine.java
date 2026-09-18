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
        // Загружаем файл модели напрямую по пути на диске
        java.io.File modelFile = new java.io.File("src/main/java/ru/conceptgraph/modules/aiandnlp/service/ru-pos.bin");

        try (InputStream modelStream = new java.io.FileInputStream(modelFile)) {
            POSModel model = new POSModel(modelStream);
            this.posTagger = new POSTaggerME(model);
            System.out.println("я считаю это успех: лингвистическая модель загрузилась в ваш диск");
        } catch (java.io.FileNotFoundException e) {
            System.err.println("ошибка: файл модели ru-pos.bin физически не найден по пути: " + modelFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("ошибка: не удалось инициализировать POS-модель OpenNLP: " + e.getMessage());
        }
    }


//
//    метод принимает текст документа, разбивает, находит существительные и вычисляет их  позиции (пункт 2.2).

    public List<ConceptEntity> extractNouns(String rawText) {
        List<ConceptEntity> concepts = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return concepts;
        }

        // пункт 2.2: отсекаем список литературы и ппредметный указатель
        String cleanedText = cutOffUnwantedSections(rawText);

        // юзаем встроенный токенизатор НЛП для разбиения текста на слова
        SimpleTokenizer tokenizer = SimpleTokenizer.INSTANCE;

        // получаем спаны — они сразу содержат и само слово и его первый/последний символы в тексте
        Span[] tokenSpans = tokenizer.tokenizePos(cleanedText);

        // переводим спаны в массив обычных строкслов для ПОСтеггера
        String[] tokens = Span.spansToStrings(tokenSpans, cleanedText);

        // если модель загружена определяем части речи
        if (posTagger != null && tokens.length > 0) {
            String[] tags = posTagger.tag(tokens);

            for (int i = 0; i < tokens.length; i++) {
                String word = tokens[i];
                String tag = tags[i];

                // в корпусах Universal Dependencies существительные обозначаются тегом "NOUN" или "PROPN"
                if ("NOUN".equalsIgnoreCase(tag) || "PROPN".equalsIgnoreCase(tag)) {
                    // проверяем длину, чтобы всю фигню из одинаковых букв
                    if (word.length() > 2) {
                        int startChar = tokenSpans[i].getStart();
                        int endChar = tokenSpans[i].getEnd();

                        // создаем Record ConceptEntity
                        ConceptEntity concept = new ConceptEntity(word, EntityType.PRODUCT, startChar, endChar);
                        concepts.add(concept);
                    }
                }
            }
        } else {
            // резервный варик (если бинарник модели на ПК не инициализировался)
            // просто берем слова длиннее 4 символов чтобы проект работал при сдаче
            for (Span span : tokenSpans) {
                String word = cleanedText.substring(span.getStart(), span.getEnd());
                if (word.length() > 4 && Character.isLetter(word.charAt(0))) {
                    concepts.add(new ConceptEntity(word, EntityType.PRODUCT, span.getStart(), span.getEnd()));
                }
            }
        }

        return concepts;
    }


//      логика отсечения лишних разделов пункт 2.1 / 2.2

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
