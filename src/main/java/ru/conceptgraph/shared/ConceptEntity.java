package ru.conceptgraph.shared;

public record ConceptEntity(Long conceptId, String value, EntityType type, int startChar, int endChar) {
}
