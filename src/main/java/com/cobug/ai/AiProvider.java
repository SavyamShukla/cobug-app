package com.cobug.ai;

import java.util.*;

public interface AiProvider {
    String answer(UUID projectId, String email, String question);

    default String answer(UUID projectId, String email, String question, List<Map<String,String>> history) {
        return answer(projectId, email, question);
    }

    default String answer(UUID projectId, String email, String question,
                          List<Map<String,String>> history, String modelOverride) {
        return answer(projectId, email, question, history);
    }

    default String answerGlobal(String email, String question, List<Map<String,String>> history) {
        throw new UnsupportedOperationException("Global AI is not implemented by this provider.");
    }

    default String answerGlobal(String email, String question, List<Map<String,String>> history,
                                String modelOverride) {
        return answerGlobal(email, question, history);
    }

    String name();
    String model();
}
