package com.cobug.auth;

public record OtpChallengeResponse(String challengeId, int expiresInSeconds) {}
