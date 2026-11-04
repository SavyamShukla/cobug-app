import os
import sys
from pathlib import Path
import PyInstaller.__main__

ROOT = Path(__file__).resolve().parent

args = [
    str(ROOT / "app.py"),
    "--name=cobug-intent",
    "--onedir",
    "--noconfirm",
    "--clean",
    "--distpath=" + str(ROOT / "dist"),
    "--workpath=" + str(ROOT / "build"),
    "--specpath=" + str(ROOT),
    "--hidden-import=uvicorn.logging",
    "--hidden-import=uvicorn.loops",
    "--hidden-import=uvicorn.loops.auto",
    "--hidden-import=uvicorn.protocols",
    "--hidden-import=uvicorn.protocols.http",
    "--hidden-import=uvicorn.protocols.http.auto",
    "--hidden-import=uvicorn.protocols.websockets",
    "--hidden-import=uvicorn.protocols.websockets.auto",
    "--hidden-import=uvicorn.lifespan",
    "--hidden-import=uvicorn.lifespan.on",
    "--collect-data=transformers",
    "--collect-data=torch",
    "--copy-metadata=transformers",
    "--copy-metadata=torch",
    "--copy-metadata=tqdm",
    "--copy-metadata=regex",
    "--copy-metadata=requests",
    "--copy-metadata=packaging",
    "--copy-metadata=filelock",
    "--copy-metadata=numpy",
    "--copy-metadata=huggingface_hub",
    "--copy-metadata=safetensors",
]

if __name__ == "__main__":
    print(f"Building standalone intent detector binary with PyInstaller...")
    PyInstaller.__main__.run(args)
    print("Build complete. Output in:", ROOT / "dist" / "cobug-intent")
