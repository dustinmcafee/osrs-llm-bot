# Windows Setup Guide (PowerShell)

Complete guide to fine-tuning Llama 3.1 8B on a Windows machine with an RTX 3080, then serving it via LM Studio.

**Assumes:** You already have `.jsonl` training data files ready.

---

## Step 1: Install Prerequisites

### 1a. NVIDIA Driver

You just need the standard NVIDIA GPU driver (you almost certainly already have it). PyTorch bundles its own CUDA runtime, so you do **not** need the standalone CUDA Toolkit installer.

Verify your driver is working:
```powershell
nvidia-smi
```
You should see your RTX 3080 listed with a driver version.

### 1b. Python 3.11

Install Python 3.11 specifically — 3.12+ breaks some dependencies (unsloth, bitsandbytes).

```powershell
winget install Python.Python.3.11
```

Close and reopen PowerShell, then verify:
```powershell
py -3.11 --version
```

If you have multiple Python versions installed, use `py -3.11` instead of `python` throughout this guide.

### 1c. Git

Download from https://git-scm.com/download/win if you don't have it.

---

## Step 2: Create Project Folder

```powershell
mkdir C:\osrs-llm
mkdir C:\osrs-llm\data
cd C:\osrs-llm
```

Copy your `.jsonl` training file(s) into `C:\osrs-llm\data\`. The main file should be named `train.jsonl`.

---

## Step 3: Set Up Python Environment

```powershell
py -3.11 -m venv venv
.\venv\Scripts\Activate.ps1
```

You should see `(venv)` in your prompt now. Once activated, `python` inside the venv will be 3.11.

### 3a. Install PyTorch with CUDA

```powershell
pip install torch --index-url https://download.pytorch.org/whl/cu121
```

Verify GPU is visible to PyTorch:
```powershell
python -c "import torch; print('CUDA available:', torch.cuda.is_available()); print('GPU:', torch.cuda.get_device_name(0))"
```
Should print:
```
CUDA available: True
GPU: NVIDIA GeForce RTX 3080
```

### 3b. Install unsloth and dependencies

```powershell
pip install unsloth trl transformers datasets
```

> **If this fails:** unsloth works most reliably on Linux. If native Windows gives you trouble, install WSL2 (`wsl --install` in PowerShell), then run all the same commands inside WSL2's Ubuntu terminal. CUDA works natively in WSL2 with your existing GPU drivers.

---

## Step 4: Run Training

Copy `train.py` from this folder into `C:\osrs-llm\`, then run:

```powershell
cd C:\osrs-llm
.\venv\Scripts\Activate.ps1
python train.py
```

**First run:** Will download the base Llama 3.1 8B model (~5GB). This is a one-time download.

**Training time:** 1-3 hours on an RTX 3080 depending on dataset size.

### Troubleshooting

| Error | Fix |
|---|---|
| `OutOfMemoryError` / `CUDA out of memory` | In `train.py`, change `per_device_train_batch_size` from `2` to `1` |
| `torch.cuda.is_available()` returns `False` | Reinstall PyTorch: `pip install torch --index-url https://download.pytorch.org/whl/cu121` |
| `bitsandbytes` import error | `pip install bitsandbytes --prefer-binary` |
| unsloth import error on Windows | Use WSL2 instead: `wsl --install`, then run everything in WSL2 |

### What to expect during training

You'll see output like:
```
{'loss': 1.234, 'learning_rate': 0.0002, 'epoch': 0.5}
{'loss': 0.876, 'learning_rate': 0.00015, 'epoch': 1.0}
{'loss': 0.654, 'learning_rate': 0.0001, 'epoch': 1.5}
...
Training complete!
GGUF saved to output/gguf/
```

The `loss` value should generally decrease over time. If it goes to 0.0 or stays very high, something is wrong with your data.

---

## Step 5: Find Your GGUF File

After training completes, the GGUF file will be at:
```
C:\osrs-llm\output\gguf\unsloth.Q4_K_M.gguf
```

This is the only file you need going forward. It contains the entire fine-tuned model.

---

## Step 6: Install LM Studio

Download from https://lmstudio.ai/ and install it.

---

## Step 7: Load the Model in LM Studio

### 7a. Copy GGUF to LM Studio's model folder

```powershell
$lmDir = "$env:USERPROFILE\.cache\lm-studio\models\osrs-llama-8b"
New-Item -ItemType Directory -Path $lmDir -Force
Copy-Item "C:\osrs-llm\output\gguf\*.gguf" -Destination $lmDir
```

### 7b. Load the model

1. Open LM Studio
2. Click the **Search** bar at the top
3. Type `osrs-llama-8b` — your model should appear under "My Models"
4. Click it to load

### 7c. Configure model settings

In the right panel after loading:

| Setting | Value |
|---|---|
| Context Length | `4096` |
| GPU Offload | `max` (all layers on GPU) |
| Temperature | `0.7` (or match what your bot uses) |

---

## Step 8: Start the API Server

1. Click the **Developer** tab in the left sidebar (the `<>` icon)
2. Click **Start Server**
3. Server runs at `http://localhost:1234`

You should see:
```
Server started on http://localhost:1234
```

---

## Step 9: Test It

### Quick test from PowerShell

```powershell
$body = @{
    model = "osrs-llama-8b"
    messages = @(
        @{
            role = "user"
            content = "What level do I need to mine iron ore in OSRS?"
        }
    )
    temperature = 0.7
    max_tokens = 500
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Uri "http://localhost:1234/v1/chat/completions" `
    -Method Post -Body $body -ContentType "application/json"

$response.choices[0].message.content
```

Or use the included `test_server.ps1` script.

### Test with bot-style input

```powershell
$body = @{
    model = "osrs-llama-8b"
    messages = @(
        @{
            role = "system"
            content = "You are an OSRS bot controller. Respond with a JSON array of actions."
        },
        @{
            role = "user"
            content = "Location: Lumbridge. Inventory: empty. Task: mine copper ore."
        }
    )
    temperature = 0.3
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Uri "http://localhost:1234/v1/chat/completions" `
    -Method Post -Body $body -ContentType "application/json"

$response.choices[0].message.content
```

---

## Step 10: Connect Your Bot

On your Ubuntu machine, update your proxy config to point at the Windows machine:

```
http://<WINDOWS-LOCAL-IP>:1234/v1/chat/completions
```

Find your Windows local IP:
```powershell
(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike "*Loopback*" -and $_.IPAddress -notlike "169.*" }).IPAddress
```

### Windows Firewall

You may need to allow inbound connections on port 1234:
```powershell
New-NetFirewallRule -DisplayName "LM Studio API" -Direction Inbound -Port 1234 -Protocol TCP -Action Allow
```

### Proxy code change

Your proxy currently sends Anthropic-format requests. LM Studio uses OpenAI format. The key differences:

| | Anthropic API | LM Studio (OpenAI format) |
|---|---|---|
| Endpoint | `/v1/messages` | `/v1/chat/completions` |
| System prompt | Top-level `system` field | `messages[0]` with `role: "system"` |
| Response | `content[0].text` | `choices[0].message.content` |

---

## Maintenance

### Retraining with new data

Just add more data to your `.jsonl`, re-run `python train.py`, and replace the GGUF in LM Studio's model folder. Restart the model in LM Studio to pick up the new version.

### Model isn't good enough?

- **More data** is usually the answer. Aim for 1000+ high-quality examples.
- **Increase epochs** from 3 to 5 in `train.py` (risk of overfitting with small datasets).
- **Increase LoRA rank** from 16 to 32 (uses more VRAM).
- **Try a bigger model** if you upgrade your GPU later (Llama 70B needs ~40GB VRAM for QLoRA).
