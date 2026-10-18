package com.example.cards.service;

/** Result of an idempotent operation: the HTTP status to return, the body, and whether it was a replay. */
public record Outcome<T>(int status, T body, boolean replayed) {
}
