# COBUG intent detector

This CPU FastAPI service is called by the Spring Boot backend at `http://127.0.0.1:8091`. It does not answer users itself: it returns an intent prediction to the backend router.

## Model files
Place your trained checkpoint files in `model/`: `config.json`, `model.safetensors`, `tokenizer.json`, `tokenizer_config.json`, `cobug_labels.json`, and the separate `head.pt` classifier export. Keep the encoder weights and classifier head from the same training run. The service explicitly loads `head.pt`; it refuses predictions if the head is missing or incompatible rather than using random classifier weights.

## Run on Windows (Development)
Activate `C:\cobug-intent-venv`, `cd` to this folder, then run `python -m uvicorn app:app --host 127.0.0.1 --port 8091`. Check `/health`; `modelLoaded` must be true and `labels` should be 51.

## Package for Desktop App (Production Standalone)
To bundle the intent detector directly into the desktop installer without requiring Python on the end-user machine:
```powershell
# From agent/intent-detector directory:
C:\cobug-intent-venv\Scripts\python.exe build_exe.py
```
This generates a self-contained directory in `dist/cobug-intent/`. When building the Electron desktop installer (`npm run dist:win`), Electron automatically embeds this binary into `extraResources/bin/cobug-intent` and launches it in the background as a local sidecar service.

