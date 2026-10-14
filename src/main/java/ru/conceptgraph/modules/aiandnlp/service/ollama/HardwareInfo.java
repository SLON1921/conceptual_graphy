package ru.conceptgraph.modules.aiandnlp.service.ollama;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Железо компьютера, от которого зависит, какая модель Ollama потянет:
 * оперативка (Java знает её сама) и видеокарта NVIDIA (спрашиваем у nvidia-smi).
 *
 * @param ramGb  оперативка в ГБ, 0 — не удалось узнать
 * @param gpuName название видеокарты, пусто — NVIDIA не найдена
 * @param vramGb память видеокарты в ГБ, 0 — нет видеокарты NVIDIA
 */
public record HardwareInfo(int ramGb, String gpuName, int vramGb) {

    public static HardwareInfo detect() {
        int ram = detectRamGb();
        String[] gpu = detectNvidiaGpu();
        if (gpu == null) {
            return new HardwareInfo(ram, "", 0);
        }
        return new HardwareInfo(ram, gpu[0], Integer.parseInt(gpu[1]));
    }

    private static int detectRamGb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            // 32 ГБ система показывает как 31,8 — округляем
            return (int) Math.round(os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
        } catch (Throwable e) {
            return 0; // не HotSpot или нет доступа — пусть пользователь введёт сам
        }
    }

    /** Название и память (ГБ) первой видеокарты NVIDIA или null, если nvidia-smi нет */
    private static String[] detectNvidiaGpu() {
        try {
            Process process = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine(); // «NVIDIA GeForce RTX 5070, 12227»
            }
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0 || line == null) {
                process.destroy();
                return null;
            }
            String[] parts = line.split(",");
            if (parts.length < 2) {
                return null;
            }
            long mb = Long.parseLong(parts[1].trim());
            return new String[]{parts[0].trim(), String.valueOf(Math.round(mb / 1024.0))};
        } catch (Exception e) {
            return null; // nvidia-smi нет: видеокарта не NVIDIA или драйвер не стоит
        }
    }
}
