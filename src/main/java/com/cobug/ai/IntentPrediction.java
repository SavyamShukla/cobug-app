package com.cobug.ai;

public record IntentPrediction(String intent, double confidence, boolean modelAvailable, String detail) {}
