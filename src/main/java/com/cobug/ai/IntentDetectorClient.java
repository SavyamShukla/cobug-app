package com.cobug.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

/**
 * Calls the separately hosted local detector service. The trained model stays out of the
 * Spring Boot artifact and can be dropped into agent/intent-detector/model/.
 */
@Service
public class IntentDetectorClient {
    private final String endpoint;
    private final double threshold;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public IntentDetectorClient(
            @Value("${cobug.intent-detector.url:http://127.0.0.1:8091}") String endpoint,
            @Value("${cobug.intent-detector.threshold:0.15}") double threshold,
            ObjectMapper mapper) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.threshold = threshold;
        this.mapper = mapper;
    }

    public IntentPrediction predict(String message) {
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("text", message));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/predict"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = response.body() == null ? "" : response.body();
                return unavailable("Detector returned HTTP " + response.statusCode()
                        + (responseBody.isBlank() ? "" : ": " + responseBody));
            }
            JsonNode json = mapper.readTree(response.body());
            String intent = json.path("intent").asText("UNKNOWN_OR_AMBIGUOUS");
            double confidence = json.path("confidence").asDouble(0.0);
            if (confidence < threshold) {
                return new IntentPrediction("UNKNOWN_OR_AMBIGUOUS", confidence, true,
                        "Prediction confidence is below threshold " + threshold + "; predicted " + intent);
            }
            return new IntentPrediction(intent, confidence, true, "Model prediction accepted");
        } catch (Exception e) {
            return unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private IntentPrediction unavailable(String detail) {
        return new IntentPrediction("UNKNOWN_OR_AMBIGUOUS", 0.0, false, detail);
    }
}
