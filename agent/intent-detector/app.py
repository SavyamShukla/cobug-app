import json
import os
import sys
from pathlib import Path
from typing import Any

import torch
from torch import nn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
# pyrefly: ignore[missing-import]
from transformers import AutoModel, AutoTokenizer

ROOT = Path(__file__).resolve().parent

def resolve_model_dir() -> Path:
    env_dir = os.getenv("COBUG_INTENT_MODEL_DIR")
    if env_dir:
        p = Path(env_dir).resolve()
        if p.exists():
            return p
    # If running as a PyInstaller frozen executable
    if getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
        # Candidate 1: model/ right beside executable (onedir mode)
        if (exe_dir / "model").exists():
            return (exe_dir / "model").resolve()
        # Candidate 2: resources/model in Electron app
        if (exe_dir.parent / "model").exists():
            return (exe_dir.parent / "model").resolve()
        if (exe_dir.parent.parent / "model").exists():
            return (exe_dir.parent.parent / "model").resolve()
    # Development fallback
    return (ROOT / "model").resolve()

MODEL_DIR = resolve_model_dir()
DEVICE = torch.device("cpu")
MAX_LENGTH = int(os.getenv("COBUG_INTENT_MAX_LENGTH", "48"))
DEFAULT_THRESHOLD = float(os.getenv("COBUG_INTENT_THRESHOLD", "0.70"))

app = FastAPI(title="COBUG Intent Detector", version="2.0.0")
tokenizer = None
model = None
id2label: dict[int, str] = {}
load_error: str | None = None
threshold = DEFAULT_THRESHOLD


class PredictRequest(BaseModel):
    text: str


class IntentClassifier(nn.Module):
    """
    Matches the training notebook exactly:
      AutoModel encoder -> attention-mask-aware mean pooling -> Dropout(0.1) -> Linear head.
    The encoder weights live in model.safetensors and the trained linear layer in head.pt.
    """
    def __init__(self, encoder: nn.Module, n_labels: int):
        super().__init__()
        self.encoder = encoder
        self.drop = nn.Dropout(0.1)
        self.head = nn.Linear(self.encoder.config.hidden_size, n_labels)

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        hidden = self.encoder(
            input_ids=input_ids,
            attention_mask=attention_mask,
        ).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(dtype=hidden.dtype)
        pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
        return self.head(self.drop(pooled))


def read_labels() -> tuple[dict[int, str], float]:
    labels_path = MODEL_DIR / "cobug_labels.json"
    if not labels_path.exists():
        raise FileNotFoundError(f"Missing label metadata: {labels_path}")

    data = json.loads(labels_path.read_text(encoding="utf-8"))
    raw = data.get("id2label")
    if not isinstance(raw, dict) or not raw:
        raise ValueError("cobug_labels.json must contain a non-empty id2label object")

    labels = {int(key): str(value) for key, value in raw.items()}
    expected_ids = list(range(len(labels)))
    if sorted(labels) != expected_ids:
        raise ValueError("Intent label IDs must be contiguous from 0 to number_of_labels - 1")

    saved_threshold = float(data.get("threshold", DEFAULT_THRESHOLD))
    return labels, saved_threshold


def load_trained_head(classifier: IntentClassifier) -> None:
    head_path = MODEL_DIR / "head.pt"
    if not head_path.exists():
        raise FileNotFoundError(f"Missing trained classifier head: {head_path}")

    try:
        state: Any = torch.load(head_path, map_location="cpu", weights_only=True)
    except TypeError:
        state = torch.load(head_path, map_location="cpu")

    if not isinstance(state, dict) or "weight" not in state or "bias" not in state:
        raise ValueError("head.pt must contain the trained 'weight' and 'bias' tensors")

    expected_weight = tuple(classifier.head.weight.shape)
    expected_bias = tuple(classifier.head.bias.shape)
    actual_weight = tuple(state["weight"].shape)
    actual_bias = tuple(state["bias"].shape)
    if actual_weight != expected_weight or actual_bias != expected_bias:
        raise ValueError(
            f"Classifier head dimensions mismatch: got {actual_weight}/{actual_bias}, "
            f"expected {expected_weight}/{expected_bias}"
        )

    classifier.head.load_state_dict(state, strict=True)


@app.on_event("startup")
def load_model() -> None:
    global tokenizer, model, id2label, load_error, threshold
    try:
        config_path = MODEL_DIR / "config.json"
        weights_path = MODEL_DIR / "model.safetensors"
        if not config_path.exists() or not weights_path.exists():
            raise FileNotFoundError(
                f"Expected config.json and model.safetensors in {MODEL_DIR}"
            )

        labels, saved_threshold = read_labels()
        loaded_tokenizer = AutoTokenizer.from_pretrained(
            str(MODEL_DIR), local_files_only=True
        )
        encoder = AutoModel.from_pretrained(
            str(MODEL_DIR), local_files_only=True
        )
        classifier = IntentClassifier(encoder, len(labels))
        load_trained_head(classifier)
        classifier.to(DEVICE)
        classifier.eval()

        tokenizer = loaded_tokenizer
        model = classifier
        id2label = labels
        threshold = float(os.getenv("COBUG_INTENT_THRESHOLD", str(saved_threshold)))
        load_error = None
        print(
            f"[STARTUP] Loaded trained COBUG intent model: labels={len(id2label)}, "
            f"threshold={threshold}, pooling=mean, device={DEVICE}",
            flush=True,
        )
    except Exception as exc:
        tokenizer = None
        model = None
        id2label = {}
        load_error = f"{type(exc).__name__}: {exc}"
        print(f"[STARTUP ERROR] {load_error}", flush=True)


@app.get("/")
def root():
    return {
        "service": "COBUG Intent Detector",
        "version": "2.0.0",
        "health": "/health",
        "predict": "/predict",
    }


@app.get("/health")
def health():
    return {
        "status": "ok" if model is not None and tokenizer is not None else "degraded",
        "modelLoaded": model is not None and tokenizer is not None,
        "labels": len(id2label),
        "threshold": threshold,
        "pooling": "mean",
        "modelDirectory": str(MODEL_DIR),
        "error": load_error,
    }


@app.post("/predict")
@torch.inference_mode()
def predict(req: PredictRequest):
    if model is None or tokenizer is None:
        raise HTTPException(
            status_code=503,
            detail=f"Trained model unavailable: {load_error or 'not loaded'}",
        )

    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text cannot be empty")

    inputs = tokenizer(
        text,
        truncation=True,
        padding="max_length",
        max_length=MAX_LENGTH,
        return_tensors="pt",
    )
    inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
    logits = model(
        input_ids=inputs["input_ids"],
        attention_mask=inputs["attention_mask"],
    )
    probs = torch.softmax(logits, dim=-1)[0]
    count = min(3, int(probs.shape[-1]))
    scores, indices = torch.topk(probs, k=count)

    top_predictions = []
    for score, index in zip(scores, indices):
        intent_id = int(index.item())
        top_predictions.append({
            "intent": id2label.get(intent_id, "UNKNOWN_OR_AMBIGUOUS"),
            "confidence": float(score.item()),
        })

    predicted_id = int(indices[0].item())
    predicted_intent = id2label.get(predicted_id, "UNKNOWN_OR_AMBIGUOUS")
    confidence = float(scores[0].item())
    accepted = confidence >= threshold

    response = {
        "intent": predicted_intent if accepted else "UNKNOWN_OR_AMBIGUOUS",
        "predictedIntent": predicted_intent,
        "confidence": confidence,
        "threshold": threshold,
        "accepted": accepted,
        "topPredictions": top_predictions,
    }
    print(
        f"[PREDICT] text={text!r} intent={predicted_intent} "
        f"confidence={confidence:.6f} accepted={accepted}",
        flush=True,
    )
    return response


if __name__ == "__main__":
    import multiprocessing
    import uvicorn
    multiprocessing.freeze_support()
    port = int(os.getenv("COBUG_INTENT_PORT", "8091"))
    host = os.getenv("COBUG_INTENT_HOST", "127.0.0.1")
    uvicorn.run(app, host=host, port=port, log_level="info")

