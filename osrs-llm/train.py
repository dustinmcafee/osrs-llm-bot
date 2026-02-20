"""
OSRS LLM Fine-Tuning Script
============================
Fine-tunes Llama 3.1 8B on OSRS game data using QLoRA (4-bit).
Designed for RTX 3080 (10GB VRAM).

Usage:
    python train.py

Output:
    output/merged/    - Full merged model (float16)
    output/gguf/      - GGUF file for LM Studio
"""

from unsloth import FastLanguageModel
from trl import SFTTrainer
from transformers import TrainingArguments
from datasets import load_dataset
import os

# =============================================================================
# CONFIG — adjust these as needed
# =============================================================================

# Model: pre-quantized 4-bit Llama 3.1 8B (downloads ~5GB on first run)
MODEL_NAME = "unsloth/Meta-Llama-3.1-8B-Instruct-bnb-4bit"

# Max context length (4096 is good for game state + response)
MAX_SEQ_LENGTH = 4096

# Training data file (one JSON object per line, see DATA-FORMAT.md)
DATA_FILE = "./data/train.jsonl"

# Output directories
OUTPUT_DIR = "./output"
MERGED_DIR = "./output/merged"
GGUF_DIR = "./output/gguf"

# Training hyperparameters
BATCH_SIZE = 2          # Reduce to 1 if you get CUDA OOM errors
GRAD_ACCUM_STEPS = 4    # Effective batch size = BATCH_SIZE * GRAD_ACCUM_STEPS
EPOCHS = 3              # Number of passes over the data
LEARNING_RATE = 2e-4    # Standard for LoRA fine-tuning
LORA_RANK = 16          # LoRA rank (higher = more capacity, more VRAM)
LORA_ALPHA = 16         # Usually set equal to rank

# GGUF quantization method (q4_k_m is a good quality/size balance)
# Options: q4_0, q4_k_m, q5_k_m, q8_0, f16
GGUF_QUANT = "q4_k_m"

# =============================================================================
# SANITY CHECKS
# =============================================================================

if not os.path.exists(DATA_FILE):
    print(f"ERROR: Training data file not found: {DATA_FILE}")
    print("Place your .jsonl file in the data/ folder and name it train.jsonl")
    print("See DATA-FORMAT.md for the expected format.")
    exit(1)

# Count examples
with open(DATA_FILE, "r", encoding="utf-8") as f:
    num_examples = sum(1 for line in f if line.strip())

print(f"Found {num_examples} training examples in {DATA_FILE}")
if num_examples < 50:
    print("WARNING: Very few examples. Consider gathering more data for better results.")

# =============================================================================
# 1. LOAD MODEL IN 4-BIT
# =============================================================================

print(f"\nLoading {MODEL_NAME} in 4-bit...")
model, tokenizer = FastLanguageModel.from_pretrained(
    model_name=MODEL_NAME,
    max_seq_length=MAX_SEQ_LENGTH,
    dtype=None,            # auto-detect (float16 for 30-series GPUs)
    load_in_4bit=True,     # QLoRA — fits in 10GB VRAM
)
print("Model loaded.")

# =============================================================================
# 2. ADD LoRA ADAPTERS
# =============================================================================

print(f"Adding LoRA adapters (rank={LORA_RANK})...")
model = FastLanguageModel.get_peft_model(
    model,
    r=LORA_RANK,
    target_modules=[
        "q_proj", "k_proj", "v_proj", "o_proj",  # attention
        "gate_proj", "up_proj", "down_proj",      # MLP
    ],
    lora_alpha=LORA_ALPHA,
    lora_dropout=0,        # unsloth optimized — keep at 0
    bias="none",
    use_gradient_checkpointing="unsloth",  # 30% less VRAM
    random_state=42,
)

trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
total = sum(p.numel() for p in model.parameters())
print(f"Trainable parameters: {trainable:,} / {total:,} ({100*trainable/total:.2f}%)")

# =============================================================================
# 3. LOAD AND FORMAT DATASET
# =============================================================================

print(f"\nLoading dataset from {DATA_FILE}...")
dataset = load_dataset("json", data_files=DATA_FILE, split="train")

def format_chat(example):
    """Convert the conversations list to Llama's chat template format."""
    text = tokenizer.apply_chat_template(
        example["conversations"],
        tokenize=False,
        add_generation_prompt=False,
    )
    return {"text": text}

dataset = dataset.map(format_chat)
print(f"Dataset ready: {len(dataset)} examples")

# Print a sample to verify formatting
print("\n--- Sample formatted input (first 500 chars) ---")
print(dataset[0]["text"][:500])
print("--- end sample ---\n")

# =============================================================================
# 4. TRAIN
# =============================================================================

trainer = SFTTrainer(
    model=model,
    tokenizer=tokenizer,
    train_dataset=dataset,
    dataset_text_field="text",
    max_seq_length=MAX_SEQ_LENGTH,
    packing=False,          # True can be faster but may cause issues with short examples
    args=TrainingArguments(
        per_device_train_batch_size=BATCH_SIZE,
        gradient_accumulation_steps=GRAD_ACCUM_STEPS,
        warmup_steps=10,
        num_train_epochs=EPOCHS,
        learning_rate=LEARNING_RATE,
        fp16=False,
        bf16=True,           # RTX 3080 supports bf16 with newer torch/CUDA
        logging_steps=10,
        output_dir=OUTPUT_DIR,
        save_strategy="epoch",
        optim="adamw_8bit",  # 8-bit Adam optimizer — saves VRAM
        seed=42,
        report_to="none",    # disable wandb/tensorboard
    ),
)

print("Starting training...")
print(f"  Epochs: {EPOCHS}")
print(f"  Batch size: {BATCH_SIZE} (effective: {BATCH_SIZE * GRAD_ACCUM_STEPS})")
print(f"  Learning rate: {LEARNING_RATE}")
print()

trainer.train()
print("\nTraining complete!")

# =============================================================================
# 5. SAVE MERGED MODEL
# =============================================================================

print(f"\nSaving merged model to {MERGED_DIR}...")
model.save_pretrained_merged(
    MERGED_DIR,
    tokenizer,
    save_method="merged_16bit",
)
print("Merged model saved.")

# =============================================================================
# 6. EXPORT TO GGUF
# =============================================================================

print(f"\nExporting GGUF ({GGUF_QUANT}) to {GGUF_DIR}...")
model.save_pretrained_gguf(
    GGUF_DIR,
    tokenizer,
    quantization_method=GGUF_QUANT,
)

# Find the output file
for f in os.listdir(GGUF_DIR):
    if f.endswith(".gguf"):
        size_gb = os.path.getsize(os.path.join(GGUF_DIR, f)) / (1024**3)
        print(f"\nGGUF file: {GGUF_DIR}/{f} ({size_gb:.1f} GB)")
        break

print("\n" + "=" * 60)
print("DONE!")
print("=" * 60)
print(f"\nNext steps:")
print(f"  1. Copy the .gguf file from {GGUF_DIR}\\ to LM Studio's models folder")
print(f"  2. Load it in LM Studio and start the API server")
print(f"  3. See SETUP-WINDOWS.md steps 7-10 for details")
