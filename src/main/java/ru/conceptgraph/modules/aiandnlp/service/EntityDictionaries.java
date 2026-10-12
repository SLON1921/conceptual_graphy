package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.shared.EntityType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Словари для определения типа понятия без Ollama.
 * Лежат в resources/dictionaries/*.txt, по одному слову на строку, # — комментарий.
 * Чтобы расширить словарь, достаточно дописать слово в файл.
 */
public final class EntityDictionaries {

    private static final Set<String> GEOGRAPHY = load("geography.txt");
    private static final Set<String> TECHNOLOGY = load("technology.txt");
    private static final Set<String> PERSON = load("person.txt");
    private static final Set<String> PRODUCT = load("product.txt");
    private static final Set<String> FIRST_NAMES = load("first-names.txt");

    // Окончания русских топонимов: Новосибирск, Зеленоград, Екатеринбург, Севастополь, Иваново, Пушкино
    private static final String[] GEO_SUFFIXES = {"ск", "град", "бург", "поль", "ово", "ево", "ино", "ыно"};

    private EntityDictionaries() {
    }

    /** Тип по словарю или null, если слова в словарях нет */
    public static EntityType lookup(String word) {
        String key = RussianMorphology.stemKey(word);
        if (PRODUCT.contains(key)) {
            return EntityType.PRODUCT;
        }
        if (GEOGRAPHY.contains(key)) {
            return EntityType.GEOGRAPHY;
        }
        if (TECHNOLOGY.contains(key)) {
            return EntityType.TECHNOLOGY;
        }
        if (PERSON.contains(key)) {
            return EntityType.PERSON;
        }
        return null;
    }

    public static boolean isFirstName(String word) {
        return word != null && !word.isEmpty()
                && Character.isUpperCase(word.charAt(0))
                && FIRST_NAMES.contains(RussianMorphology.stemKey(word));
    }

    public static boolean looksLikeToponym(String word) {
        String lower = word.toLowerCase().replace('ё', 'е');
        for (String suffix : GEO_SUFFIXES) {
            // суффикс может стоять перед падежным окончанием: «в Новосибирске»
            if (lower.endsWith(suffix) || lower.matches(".*" + suffix + "(а|е|у|ом|ой)$")) {
                return lower.length() > suffix.length() + 2;
            }
        }
        return false;
    }

    private static Set<String> load(String fileName) {
        Set<String> result = new HashSet<>();
        try (InputStream in = EntityDictionaries.class.getResourceAsStream("/dictionaries/" + fileName)) {
            if (in == null) {
                System.err.println("[NLP] Словарь не найден: " + fileName);
                return result;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    result.add(RussianMorphology.stemKey(line));
                }
            }
        } catch (Exception e) {
            System.err.println("[NLP] Не удалось прочитать словарь " + fileName + ": " + e.getMessage());
        }
        return result;
    }
}
