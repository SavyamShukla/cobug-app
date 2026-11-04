# COBUG intent engine integration

## Included trained model

The trained model from `cobug_intent_model3.zip` is included in:
`agent/intent-detector/model/`

The included files are:
- `config.json`
- `model.safetensors` (MiniLM encoder)
- `head.pt` (trained 51-class linear classifier)
- `tokenizer.json`
- `tokenizer_config.json`
- `cobug_labels.json` (51 label map, mean pooling, max length 48, configurable threshold; packaged default 0.15)

The detector implements the training notebook architecture: `AutoModel` encoder, attention-mask-aware mean pooling, dropout (disabled at inference), and the separately trained linear head. It validates the head dimensions and will report a degraded health status rather than serving random/untrained weights if loading fails.

## Request flow

Both authenticated chat endpoints invoke `AiIntentRoutingService` before the configured AI provider:

- `POST /api/ai/chat`
- `POST /api/projects/{projectId}/ai/chat`

The detector at `COBUG_INTENT_DETECTOR_URL` (default `http://127.0.0.1:8091`) predicts the intent. The backend applies the configured threshold (default `0.15`, overridable with `COBUG_INTENT_THRESHOLD`). The detector and Java client defaults are aligned at `0.15`. `EXTERNAL_COMPLEX`, `UNKNOWN_OR_AMBIGUOUS`, low-confidence predictions, unavailable detector, and intents without a registered local operator are delegated to the existing AI provider, preserving provider/model/credit settings.

The local engine registers deterministic operators for all 49 non-fallback intents in the 51-label catalog: all `GET_*` intents, `GREETINGS`, `SEARCH_USERS`, and `WRITE_ACTION`. Read operators use the authenticated account and project access checks; project-specific requests require a current project context or an unambiguous accessible project name. `WRITE_ACTION` is deliberately read-only: it explains that users must confirm changes through the relevant UI. `EXTERNAL_COMPLEX` and `UNKNOWN_OR_AMBIGUOUS` remain external fallbacks. Binary test-package downloads are directed to the review UI because chat responses are text, not file downloads.

Successful chat responses include `intent`, `confidence`, `route` (`LOCAL` or `EXTERNAL`), and `routingDetail` in addition to existing response fields.

## Windows setup

Run these commands from the extracted COBUG project root:

```powershell
py -3.11 -m venv C:\cobug-intent-venv
C:\cobug-intent-venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r agent\intent-detector\requirements.txt
python -m uvicorn app:app --app-dir agent\intent-detector --host 127.0.0.1 --port 8091
```

If the virtual environment already exists, activate it and install/upgrade the requirements rather than recreating it.

Check `http://127.0.0.1:8091/health`. A successful health response should contain `"modelLoaded": true`, `"labels": 51`, and `"pooling": "mean"`.

Then start/restart the Spring Boot backend. The Java client explicitly uses HTTP/1.1 for the local detector request and includes the detector's response body in non-2xx diagnostic details.

## Quick detector test

In another PowerShell terminal:

```powershell
$body = @{ text = "Show me all my projects" } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "http://127.0.0.1:8091/predict" -Method Post -ContentType "application/json" -Body $body
```

Expected prediction is `GET_MY_PROJECTS` with confidence dependent on the loaded checkpoint and text. The endpoint also returns `topPredictions`, `threshold`, and `accepted`.


## Local operator behavior

The expanded engine reads account/profile data, personal tasks, AI settings, social/friend data, notifications, project metadata/members/activity/chat/aim, public project tasks, resources, roadmap, Main and Main versions, personal Workspace/source workspace, GitHub repository/tree/file/workspace data, invitations, reviews/review files/test-package status, and contribution summaries. It does not mutate records or execute write actions from natural-language chat.

The archive's source was reviewed and the intent registry was checked against the model label map (49 locally registered labels; `EXTERNAL_COMPLEX` and `UNKNOWN_OR_AMBIGUOUS` intentionally use the existing external fallback). A full Maven package build could not be run in the packaging environment because Maven could not be downloaded, so run `mvnw -DskipTests package` in your Windows development environment before shipping.
