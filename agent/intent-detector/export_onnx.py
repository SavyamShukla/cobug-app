import json
import os
from pathlib import Path
import torch
from torch import nn
from transformers import AutoModel, AutoTokenizer

ROOT = Path(__file__).resolve().parent
MODEL_DIR = ROOT / "model"
ONNX_PATH = MODEL_DIR / "model.onnx"

class IntentClassifier(nn.Module):
    def __init__(self, encoder: nn.Module, n_labels: int):
        super().__init__()
        self.encoder = encoder
        self.head = nn.Linear(self.encoder.config.hidden_size, n_labels)

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        hidden = self.encoder(
            input_ids=input_ids,
            attention_mask=attention_mask,
        ).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(dtype=hidden.dtype)
        pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
        return self.head(pooled)

def main():
    print(f"Loading model from {MODEL_DIR}...")
    labels_path = MODEL_DIR / "cobug_labels.json"
    labels_data = json.loads(labels_path.read_text(encoding="utf-8"))
    id2label = {int(k): str(v) for k, v in labels_data["id2label"].items()}
    n_labels = len(id2label)
    print(f"Total labels: {n_labels}")

    tokenizer = AutoTokenizer.from_pretrained(str(MODEL_DIR), local_files_only=True)
    encoder = AutoModel.from_pretrained(str(MODEL_DIR), local_files_only=True)
    model = IntentClassifier(encoder, n_labels)

    head_path = MODEL_DIR / "head.pt"
    try:
        head_state = torch.load(head_path, map_location="cpu", weights_only=True)
    except TypeError:
        head_state = torch.load(head_path, map_location="cpu")
    model.head.load_state_dict(head_state, strict=True)
    model.eval()

    sample_text = "Show me the project roadmap"
    inputs = tokenizer(sample_text, truncation=True, padding="max_length", max_length=48, return_tensors="pt")
    input_ids = inputs["input_ids"]
    attention_mask = inputs["attention_mask"]

    with torch.no_grad():
        torch_logits = model(input_ids, attention_mask)
        torch_probs = torch.softmax(torch_logits, dim=-1)[0]
        top_val, top_idx = torch.topk(torch_probs, 1)
        pred_label = id2label[int(top_idx.item())]
        print(f"PyTorch prediction: {pred_label} (confidence: {top_val.item():.4f})")

    print(f"Exporting to ONNX at {ONNX_PATH}...")
    torch.onnx.export(
        model,
        (input_ids, attention_mask),
        str(ONNX_PATH),
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "logits": {0: "batch_size"},
        },
        dynamo=False,
    )

    size_mb = ONNX_PATH.stat().st_size / (1024 * 1024)
    print(f"ONNX export successful! File size: {size_mb:.2f} MB")

if __name__ == "__main__":
    main()
