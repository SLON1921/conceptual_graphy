package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.modules.aiandnlp.service.ollama.AiGraphEngine;
import ru.conceptgraph.modules.infrastructure.repository.ConnectionPool;

import java.sql.Connection;

/** Проверки «на связи ли Ollama и база». Без окна: только да или нет. */
public final class ServiceStatus {

    private ServiceStatus() {
    }

    public static boolean isOllamaAvailable() {
        try {
            return new AiGraphEngine().isAvailable();
        } catch (Throwable e) {
            return false; // считаем, что Ollama нет
        }
    }

    public static boolean isDatabaseAvailable() {
        try (Connection ignored = ConnectionPool.getConnection()) {
            return true;
        } catch (Throwable e) {
            return false; // база не настроена или не запущена
        }
    }
}