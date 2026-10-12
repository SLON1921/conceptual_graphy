package ru.conceptgraph.modules.aiandnlp.service;

import java.util.Locale;

/**
 * Простая морфология без словаря: ключ основы слова для сравнения разных падежей.
 *
 * <p>Ключ нужен только для группировки («Москва», «Москве», «Москвой» -> «москв»).
 * Показывать пользователю его нельзя: в узле графа остаётся настоящая форма слова из текста.</p>
 */
public final class RussianMorphology {

    // От длинных окончаний к коротким: сначала пробуем «ами», потом «а»
    private static final String[] ENDINGS = {
            "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими",
            "ых", "их", "ые", "ов", "ев", "ей", "ий", "ой", "ый", "ая", "яя", "ое", "ее", "ам", "ям", "ах", "ях",
            "ом", "ем", "ою", "ею", "ым", "им", "ию", "ия", "ие", "ии",
            "а", "я", "о", "е", "ё", "ы", "и", "у", "ю", "ь", "й"
    };

    /** Минимальная длина основы: «код» и «сеть» не должны превращаться в «ко» и «се» */
    private static final int MIN_STEM = 3;

    private RussianMorphology() {
    }

    /**
     * Ключ основы: нижний регистр, ё -> е, без падежного окончания.
     * Латиница (Java, Python) и аббревиатуры (МИЭТ) не меняются, кроме регистра.
     */
    public static String stemKey(String word) {
        if (word == null || word.isBlank()) {
            return "";
        }
        String lower = word.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (!isCyrillic(lower) || isAbbreviation(word.trim())) {
            return lower;
        }
        // составные слова («дата-центр») меняем только по последней части
        int dash = lower.lastIndexOf('-');
        String head = dash >= 0 ? lower.substring(0, dash + 1) : "";
        String tail = dash >= 0 ? lower.substring(dash + 1) : lower;

        for (String ending : ENDINGS) {
            if (tail.endsWith(ending) && tail.length() - ending.length() >= MIN_STEM) {
                return head + tail.substring(0, tail.length() - ending.length());
            }
        }
        return head + tail;
    }

    public static boolean isCyrillic(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.UnicodeBlock.of(word.charAt(i)) == Character.UnicodeBlock.CYRILLIC) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLatin(String word) {
        boolean hasLetter = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                if (Character.UnicodeBlock.of(c) != Character.UnicodeBlock.BASIC_LATIN) {
                    return false;
                }
                hasLetter = true;
            }
        }
        return hasLetter;
    }

    /** МИЭТ, ВОЗ, США — все буквы заглавные */
    public static boolean isAbbreviation(String word) {
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                if (!Character.isUpperCase(c)) {
                    return false;
                }
                letters++;
            }
        }
        return letters >= 2;
    }
}
