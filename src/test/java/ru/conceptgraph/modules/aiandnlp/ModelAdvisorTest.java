package ru.conceptgraph.modules.aiandnlp;

import org.junit.jupiter.api.Test;
import ru.conceptgraph.modules.aiandnlp.service.ollama.ModelAdvisor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelAdvisorTest {

    @Test
    void bigGpuGetsFast7b() {
        assertEquals("qwen2.5:7b", ModelAdvisor.recommend(32, 12).model());
    }

    @Test
    void noGpuWith16GbRamGets7b() {
        assertEquals("qwen2.5:7b", ModelAdvisor.recommend(16, 0).model());
    }

    @Test
    void laptopWith8GbRamGets3b() {
        assertEquals("qwen2.5:3b", ModelAdvisor.recommend(8, 0).model());
    }

    @Test
    void tinyLaptopGetsSmallest() {
        assertEquals("qwen2.5:1.5b", ModelAdvisor.recommend(4, 0).model());
    }
}
