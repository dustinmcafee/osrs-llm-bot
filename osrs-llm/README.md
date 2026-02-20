# OSRS LLM Fine-Tuning Guide

Fine-tune Llama 3.1 8B on OSRS game data + bot interface documentation, then serve it via LM Studio for the OSRS Claude Bot.

## Overview

| Component | What It Is |
|---|---|
| **Llama 3.1 8B** | Meta's open-source LLM (the base model) |
| **unsloth** | Python toolkit that makes fine-tuning fast and memory-efficient |
| **QLoRA** | 4-bit quantized training method — fits 8B models on 10GB VRAM |
| **GGUF** | Model file format that LM Studio uses |
| **LM Studio** | Desktop app that serves models via OpenAI-compatible API |

## Architecture

```
[Ubuntu Machine]                    [Windows Machine (3080)]

1. Scrape OSRS Wiki       --->  4. Train with unsloth (QLoRA)
2. Log gameplay sessions   USB  5. Export to GGUF
3. Format as .jsonl        -->  6. Load in LM Studio
                                7. Serve API on :1234
                                         |
                    <--- http://WINDOWS-IP:1234/v1/chat/completions
                                         |
                    OSRS Bot Proxy connects here
```

## File Structure

```
osrs-llm/
  README.md                  # This file
  SETUP-WINDOWS.md           # Step-by-step Windows setup guide
  DATA-FORMAT.md             # Training data format specification
  train.py                   # The training script
  scrape_wiki.py             # OSRS Wiki scraper (run on Ubuntu)
  format_training_data.py    # Converts scraped data to .jsonl
  example_data.jsonl         # Example training data (3 samples)
  test_server.ps1            # PowerShell script to test LM Studio API
```

## Quick Start

1. **On Ubuntu**: Run `scrape_wiki.py` and `format_training_data.py` to generate training data
2. **Transfer**: Copy the `.jsonl` file(s) to your Windows machine via USB/network
3. **On Windows**: Follow `SETUP-WINDOWS.md` step by step
4. **Point your bot proxy** at `http://WINDOWS-IP:1234/v1/chat/completions`

## Hardware Requirements

| | Minimum | Recommended |
|---|---|---|
| GPU | RTX 3080 (10GB) | RTX 3090/4090 (24GB) |
| RAM | 16GB | 32GB |
| Disk | 20GB free | 50GB free |
| Python | 3.10 or 3.11 | 3.11 |
| CUDA | 12.1+ | 12.4+ |
