package ru.conceptgraph.modules.aiandnlp.service.ollama;

import java.util.ArrayList;
import java.util.List;

/**
 * Режет длинный текст на куски для Ollama: целый рассказ в один запрос не влезает,
 * а модель на длинном тексте «забывает» начало. Режем по абзацам, чтобы не рвать мысль.
 */
public final class TextChunker {

    /** Кусок текста: символы с start (включительно) до end (не включая) */
    public record Chunk(int start, int end) {
    }

    private TextChunker() {
    }

    /**
     * @param maxWords примерно столько слов в куске; абзац длиннее режется по пробелам
     */
    public static List<Chunk> split(String text, int maxWords) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int chunkStart = 0;
        int words = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            // конец текущего абзаца — перевод строки или конец текста
            int lineEnd = text.indexOf('\n', i);
            int paragraphEnd = lineEnd == -1 ? n : lineEnd + 1;
            int paragraphWords = countWords(text, i, paragraphEnd);

            if (words > 0 && words + paragraphWords > maxWords) {
                chunks.add(new Chunk(chunkStart, i));
                chunkStart = i;
                words = 0;
            }
            if (paragraphWords > maxWords) {
                // один огромный абзац: режем его по словам
                int cut = i;
                while (countWords(text, cut, paragraphEnd) > maxWords) {
                    int next = afterWords(text, cut, maxWords);
                    chunks.add(new Chunk(chunkStart, next));
                    chunkStart = next;
                    cut = next;
                }
                words = countWords(text, cut, paragraphEnd);
            } else {
                words += paragraphWords;
            }
            i = paragraphEnd;
        }
        if (chunkStart < n && countWords(text, chunkStart, n) > 0) {
            chunks.add(new Chunk(chunkStart, n));
        }
        return chunks;
    }

    static int countWords(String text, int from, int to) {
        int count = 0;
        boolean inWord = false;
        for (int i = from; i < to; i++) {
            boolean space = Character.isWhitespace(text.charAt(i));
            if (!space && !inWord) {
                count++;
            }
            inWord = !space;
        }
        return count;
    }

    /** Позиция сразу после words слов, начиная с from (на пробеле после слова) */
    private static int afterWords(String text, int from, int words) {
        int count = 0;
        boolean inWord = false;
        for (int i = from; i < text.length(); i++) {
            boolean space = Character.isWhitespace(text.charAt(i));
            if (!space && !inWord) {
                count++;
            }
            if (space && inWord && count == words) {
                return i;
            }
            inWord = !space;
        }
        return text.length();
    }
}
