package com.cobug.ai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Routing policy:
 * 1. Detect intent with the local classifier.
 * 2. Run a deterministic local operator when one is registered.
 * 3. EXTERNAL_COMPLEX, UNKNOWN_OR_AMBIGUOUS, low-confidence, unavailable detector,
 *    and intents without an operator go to the configured external AI provider.
 */
@Service
public class AiIntentRoutingService {
    private final IntentDetectorClient detector;
    private final LocalIntentEngine engine;
    private final AiProvider externalAi;

    public AiIntentRoutingService(IntentDetectorClient detector, LocalIntentEngine engine, AiProvider externalAi) {
        this.detector = detector;
        this.engine = engine;
        this.externalAi = externalAi;
    }

    public record RoutedAnswer(String reply, String intent, double confidence, String route, String detail) {}

    public RoutedAnswer answerGlobal(String email, String question, List<Map<String, String>> history) {
        return answerGlobal(email, question, history, null, null);
    }

    public RoutedAnswer answerGlobal(String email, String question, List<Map<String, String>> history,
                                     String clientIntent, Double clientConfidence) {
        IntentPrediction prediction = resolvePrediction(question, clientIntent, clientConfidence);
        if (shouldUseLocal(prediction)) {
            var local = engine.execute(prediction.intent(), email, null, question);
            if (local.isPresent()) {
                return new RoutedAnswer(local.get(), prediction.intent(), prediction.confidence(), "LOCAL",
                        "Handled by COBUG local operator");
            }
        }
        String detail = externalReason(prediction);
        String reply = externalAi.answerGlobal(email, question, history == null ? List.of() : history);
        return new RoutedAnswer(reply, prediction.intent(), prediction.confidence(), "EXTERNAL", detail);
    }

    public RoutedAnswer answerProject(UUID projectId, String email, String question, List<Map<String, String>> history) {
        return answerProject(projectId, email, question, history, null, null);
    }

    public RoutedAnswer answerProject(UUID projectId, String email, String question, List<Map<String, String>> history,
                                      String clientIntent, Double clientConfidence) {
        IntentPrediction prediction = resolvePrediction(question, clientIntent, clientConfidence);
        if (shouldUseLocal(prediction)) {
            var local = engine.execute(prediction.intent(), email, projectId, question);
            if (local.isPresent()) {
                return new RoutedAnswer(local.get(), prediction.intent(), prediction.confidence(), "LOCAL",
                        "Handled by COBUG local operator");
            }
        }
        String reply = externalAi.answer(projectId, email, question, history == null ? List.of() : history);
        return new RoutedAnswer(reply, prediction.intent(), prediction.confidence(), "EXTERNAL",
                externalReason(prediction));
    }

    private IntentPrediction resolvePrediction(String question, String clientIntent, Double clientConfidence) {
        if (clientIntent != null && !clientIntent.isBlank() && clientConfidence != null && clientConfidence > 0.0) {
            return new IntentPrediction(clientIntent, clientConfidence, true, "Handled via desktop client predictor");
        }
        IntentPrediction p = detector.predict(question);
        if (p.modelAvailable()) {
            return p;
        }
        return heuristicPredict(question, p.detail());
    }

    private IntentPrediction heuristicPredict(String question, String failureDetail) {
        if (question == null || question.isBlank()) {
            return new IntentPrediction("UNKNOWN_OR_AMBIGUOUS", 0.0, false, failureDetail);
        }
        String q = question.toLowerCase(java.util.Locale.ROOT).trim();

        if (q.matches(".*\\b(task|tasks|todo|todos|to-do|to-dos|assigned)\\b.*")) {
            return new IntentPrediction("GET_MY_TASKS", 0.95, true, "Heuristic match for tasks");
        }
        if (q.matches(".*\\b(roadmap|milestone|milestones|station|stations|stages)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_ROADMAP", 0.95, true, "Heuristic match for roadmap");
        }
        if (q.matches(".*\\b(member|members|team|who is in|who is on|collaborators|contributors)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_MEMBERS", 0.95, true, "Heuristic match for members");
        }
        if (q.matches(".*\\b(activity|activities|recent activity|recent actions|what happened|timeline)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_ACTIVITY", 0.95, true, "Heuristic match for activity");
        }
        if (q.matches(".*\\b(aim|goal|purpose|objective)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_AIM", 0.95, true, "Heuristic match for aim");
        }
        if (q.matches(".*\\b(file tree|files|file list|directory|directories|folder|browse files)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_FILE_TREE", 0.95, true, "Heuristic match for file tree");
        }
        if (q.matches(".*\\b(resource|resources|links|assets)\\b.*")) {
            return new IntentPrediction("GET_PROJECT_RESOURCES", 0.95, true, "Heuristic match for resources");
        }
        if (q.matches(".*\\b(my projects|all projects|list projects|projects)\\b.*")) {
            return new IntentPrediction("GET_MY_PROJECTS", 0.95, true, "Heuristic match for projects");
        }
        if (q.matches(".*\\b(my profile|profile|who am i|account info)\\b.*")) {
            return new IntentPrediction("GET_MY_PROFILE", 0.95, true, "Heuristic match for profile");
        }
        if (q.matches(".*\\b(ai settings|ai model|ai provider|what ai|ai config|ai status)\\b.*")) {
            return new IntentPrediction("GET_AI_SETTINGS", 0.95, true, "Heuristic match for AI settings");
        }
        if (q.matches(".*\\b(notifications|unread notifications|alerts)\\b.*")) {
            return new IntentPrediction("GET_NOTIFICATIONS", 0.95, true, "Heuristic match for notifications");
        }
        if (q.matches(".*\\b(github repo|github repository|repositories|repo)\\b.*")) {
            return new IntentPrediction("GET_GITHUB_REPOSITORIES", 0.95, true, "Heuristic match for github");
        }
        if (q.matches(".*\\b(review|reviews|code review|pull request|prs)\\b.*")) {
            return new IntentPrediction("GET_REVIEWS", 0.95, true, "Heuristic match for reviews");
        }
        if (q.matches(".*\\b(hi|hello|hey|good morning|good afternoon|good evening|greetings)\\b.*")) {
            return new IntentPrediction("GREETINGS", 0.95, true, "Heuristic match for greeting");
        }

        return new IntentPrediction("UNKNOWN_OR_AMBIGUOUS", 0.0, false, failureDetail);
    }

    private boolean shouldUseLocal(IntentPrediction prediction) {
        return prediction.modelAvailable()
                && !"EXTERNAL_COMPLEX".equals(prediction.intent())
                && !"UNKNOWN_OR_AMBIGUOUS".equals(prediction.intent())
                && engine.canHandle(prediction.intent());
    }

    private String externalReason(IntentPrediction p) {
        if (!p.modelAvailable()) return "Intent detector unavailable; safe fallback to configured AI provider. " + p.detail();
        if ("EXTERNAL_COMPLEX".equals(p.intent())) return "Classifier marked the request as complex/external.";
        if ("UNKNOWN_OR_AMBIGUOUS".equals(p.intent())) return "Request is ambiguous or below the confidence threshold.";
        if (!engine.canHandle(p.intent())) return "No local operator is registered for " + p.intent() + "; delegated to AI provider.";
        return p.detail();
    }
}
