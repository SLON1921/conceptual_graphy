package ru.conceptgraph.core.errors;

public class AiException extends RuntimeException{
    public AiException(String message, Throwable cause){
        super(message, cause);
    }
}
