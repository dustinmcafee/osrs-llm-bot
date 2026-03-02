import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

fig, ax = plt.subplots(1, 1, figsize=(16, 8))
fig.patch.set_facecolor('#0d1117')
ax.set_facecolor('#0d1117')
ax.set_xlim(0, 16)
ax.set_ylim(0, 8)
ax.axis('off')

def draw_box(ax, x, y, w, h, label, color='#58a6ff', fontsize=10, sublabel=''):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.12",
                          facecolor=color + '20', edgecolor=color, linewidth=2)
    ax.add_patch(box)
    if sublabel:
        ax.text(x + w/2, y + h/2 + 0.12, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')
        ax.text(x + w/2, y + h/2 - 0.16, sublabel, ha='center', va='center',
                color='#8b949e', fontsize=7)
    else:
        ax.text(x + w/2, y + h/2, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')

def draw_arrow(ax, x1, y1, x2, y2, color='#c9d1d9'):
    style = "Simple,tail_width=2,head_width=10,head_length=6"
    arrow = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style, color=color, linewidth=1.5)
    ax.add_patch(arrow)

ax.text(8, 7.5, 'Training Data Pipeline', color='#e6edf3', fontsize=20, fontweight='bold', ha='center')

ax.text(1.8, 6.8, 'Data Sources', color='#58a6ff', fontsize=13, fontweight='bold', ha='center')
draw_box(ax, 0.3, 5.8, 3, 0.7, 'Wiki Q&A', '#58a6ff', 10, '4,491 entries')
draw_box(ax, 0.3, 4.8, 3, 0.7, 'Live Gameplay', '#3fb950', 10, '432 turns (gold)')
draw_box(ax, 0.3, 3.8, 3, 0.7, 'Synthetic', '#d29922', 10, '7,500 scenarios')
draw_box(ax, 0.3, 2.8, 3, 0.7, 'Auto-Retaliate', '#bc8cff', 10, '26 sequences')
draw_box(ax, 0.3, 1.8, 3, 0.7, 'Gold Examples', '#f0883e', 10, '12 hand-crafted')

ax.text(6.5, 6.8, 'Processing', color='#3fb950', fontsize=13, fontweight='bold', ha='center')
draw_box(ax, 4.5, 5.3, 2, 1, 'Distill', '#3fb950', 11, 'filter + deduplicate\n+ score priority')
draw_box(ax, 4.5, 3.5, 2, 1, 'Merge', '#3fb950', 11, 'combine sources\n+ 3x weight gold')
draw_box(ax, 4.5, 1.8, 2, 0.7, 'Validate', '#3fb950', 10, 'format checks')

draw_arrow(ax, 3.3, 6.15, 4.5, 5.8, '#79c0ff')
draw_arrow(ax, 3.3, 5.15, 4.5, 5.55, '#56d364')
draw_arrow(ax, 3.3, 4.15, 4.5, 4.0, '#e3b341')
draw_arrow(ax, 3.3, 3.15, 4.5, 3.8, '#d2a8ff')
draw_arrow(ax, 3.3, 2.15, 4.5, 3.7, '#f0883e')
draw_arrow(ax, 5.5, 5.3, 5.5, 4.55, '#56d364')
draw_arrow(ax, 5.5, 3.5, 5.5, 2.55, '#56d364')

ax.text(9.5, 6.8, 'Training', color='#d29922', fontsize=13, fontweight='bold', ha='center')
draw_box(ax, 7.5, 4.5, 4, 2, '', '#d29922', 11)
ax.text(9.5, 6.1, 'QLoRA Fine-Tune', ha='center', color='#d29922', fontsize=12, fontweight='bold')
ax.text(9.5, 5.6, 'Base: Llama 3.1 8B (4-bit)', ha='center', color='#e6edf3', fontsize=9)
ax.text(9.5, 5.25, 'LoRA rank: 16  |  Epochs: 3', ha='center', color='#c9d1d9', fontsize=8)
ax.text(9.5, 4.95, 'LR: 2e-4  |  Batch: 2', ha='center', color='#c9d1d9', fontsize=8)
ax.text(9.5, 4.65, 'Hardware: RTX 3080 (10GB)', ha='center', color='#c9d1d9', fontsize=8)

draw_box(ax, 8, 2.5, 3, 1, 'train.jsonl', '#d29922', 11, '~21K examples')
draw_arrow(ax, 6.5, 2.15, 8, 2.8, '#56d364')
draw_arrow(ax, 9.5, 3.5, 9.5, 4.5, '#e3b341')

ax.text(13.8, 6.8, 'Serving', color='#bc8cff', fontsize=13, fontweight='bold', ha='center')
draw_box(ax, 12.3, 5.3, 3, 1, 'Merged Model', '#bc8cff', 11, 'float16 weights')
draw_box(ax, 12.3, 3.8, 3, 0.8, 'GGUF Export', '#bc8cff', 10, 'q4_k_m quantized')
draw_box(ax, 12.3, 2.3, 3, 1, 'LM Studio', '#bc8cff', 11, 'localhost:1234/v1')

draw_arrow(ax, 11.5, 5.3, 12.3, 5.6, '#e3b341')
draw_arrow(ax, 13.8, 5.3, 13.8, 4.65, '#d2a8ff')
draw_arrow(ax, 13.8, 3.8, 13.8, 3.35, '#d2a8ff')

arrow = FancyArrowPatch((13.8, 2.3), (1.8, 1.2), connectionstyle="arc3,rad=-0.15",
                         arrowstyle="Simple,tail_width=2,head_width=10,head_length=6",
                         color='#ff7b72', linewidth=2, linestyle='--')
ax.add_patch(arrow)
ax.text(8, 0.9, 'feedback loop: local model plays \u2192 proxy logs new training data \u2192 re-distill \u2192 re-train',
        ha='center', color='#ff7b72', fontsize=9, style='italic')

plt.tight_layout()
plt.savefig('/home/dustin/workingdir/apk_source/osrs/docs/images/training-pipeline.png',
            dpi=150, facecolor='#0d1117', edgecolor='none', bbox_inches='tight')
plt.close()
print("Done: training-pipeline.png")
