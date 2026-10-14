package ru.conceptgraph.modules.aiandnlp.service.ollama;

/**
 * Подбирает модель Ollama под железо. Берём семейство qwen2.5: оно лучше llama3 понимает русский.
 * Модель должна целиком влезть в память видеокарты, иначе — в оперативку (тогда считает процессор).
 */
public final class ModelAdvisor {

    /**
     * @param model  имя для ollama pull
     * @param sizeGb примерный размер скачивания
     * @param reason почему именно она, для окна
     */
    public record Recommendation(String model, double sizeGb, String reason) {
    }

    /** Все модели, которые предлагаем в окне, от маленькой к большой */
    public static final String[] MODELS = {"qwen2.5:1.5b", "qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b"};

    private ModelAdvisor() {
    }

    public static Recommendation recommend(int ramGb, int vramGb) {
        // 14b тоже влезает в видеокарту от 10 ГБ, но вдвое медленнее — её можно выбрать в окне вручную
        if (vramGb >= 6) {
            return new Recommendation("qwen2.5:7b", 4.7,
                    "Видеокарта " + vramGb + " ГБ: модель считает на видеокарте, быстро.");
        }
        if (ramGb >= 16) {
            return new Recommendation("qwen2.5:7b", 4.7,
                    "Оперативка " + ramGb + " ГБ: модель считает на процессоре, медленнее, но качество хорошее.");
        }
        if (ramGb >= 8) {
            return new Recommendation("qwen2.5:3b", 1.9,
                    "Оперативка " + ramGb + " ГБ: большая модель не поместится, берём поменьше.");
        }
        return new Recommendation("qwen2.5:1.5b", 1.0,
                "Мало памяти: самая маленькая модель, связи будут проще.");
    }
}
