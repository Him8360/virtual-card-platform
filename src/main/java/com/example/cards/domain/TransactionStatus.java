package com.example.cards.domain;

/** PENDING is reserved for a future authorize/capture flow; today every transaction settles immediately. */
public enum TransactionStatus { SUCCESSFUL, DECLINED, PENDING }
