import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

# Use a bright arrow color throughout
ARROW = '#c9d1d9'

fig, ax = plt.subplots(1, 1, figsize=(16, 9))
fig.patch.set_facecolor('#0d1117')
ax.set_facecolor('#0d1117')
ax.set_xlim(0, 16)
ax.set_ylim(0, 9)
ax.axis('off')

def draw_box(ax, x, y, w, h, label, sublabel='', color='#58a6ff', fontsize=11):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15",
                          facecolor=color + '20', edgecolor=color, linewidth=2)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2 + (0.12 if sublabel else 0), label,
            ha='center', va='center', color='#e6edf3', fontsize=fontsize, fontweight='bold')
    if sublabel:
        ax.text(x + w/2, y + h/2 - 0.2, sublabel,
                ha='center', va='center', color='#8b949e', fontsize=8)

def draw_arrow(ax, x1, y1, x2, y2, color=ARROW, label=''):
    style = "Simple,tail_width=2,head_width=10,head_length=7"
    arrow = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style, color=color, linewidth=1.5)
    ax.add_patch(arrow)
    if label:
        mx, my = (x1+x2)/2, (y1+y2)/2 + 0.2
        ax.text(mx, my, label, ha='center', va='center', color=color, fontsize=8, style='italic')

ax.text(8, 8.5, 'System Architecture', color='#e6edf3', fontsize=22, fontweight='bold', ha='center')

# RuneLite Plugin group
grp1 = FancyBboxPatch((0.3, 1.5), 5.4, 5.8, boxstyle="round,pad=0.2",
                        facecolor='#58a6ff08', edgecolor='#58a6ff', linewidth=1.5, linestyle='--')
ax.add_patch(grp1)
ax.text(3, 7.05, 'RuneLite Plugin (Java)', color='#58a6ff', fontsize=13, fontweight='bold', ha='center')

draw_box(ax, 0.6, 5.8, 2.2, 0.8, 'Game State', 'Reader', '#58a6ff')
draw_box(ax, 0.6, 4.5, 2.2, 0.8, 'Serializer', 'text format', '#58a6ff')
draw_box(ax, 3.2, 5.8, 2.2, 0.8, 'Response', 'Parser + Aliases', '#58a6ff')
draw_box(ax, 3.2, 4.5, 2.2, 0.8, 'Action', 'Executor (3-phase)', '#58a6ff')
draw_box(ax, 0.6, 2.8, 2.2, 0.8, 'A* Pathfinder', '3886 transports', '#58a6ff')
draw_box(ax, 3.2, 2.8, 2.2, 0.8, 'Human Sim', 'Bezier + timing', '#58a6ff')
draw_box(ax, 1.9, 1.7, 2.2, 0.8, 'OSRS Client', 'EventQueue\u2192Canvas', '#f0883e')

draw_arrow(ax, 1.7, 5.8, 1.7, 5.35, '#79c0ff')
draw_arrow(ax, 4.3, 5.8, 4.3, 5.35, '#79c0ff')
draw_arrow(ax, 4.3, 4.5, 4.3, 3.65, '#79c0ff')
draw_arrow(ax, 3.2, 3.2, 2.85, 3.2, '#79c0ff')
draw_arrow(ax, 4.3, 2.8, 3.8, 2.55, '#79c0ff')
draw_arrow(ax, 1.7, 2.8, 2.5, 2.55, '#79c0ff')

# Proxy group
grp2 = FancyBboxPatch((6.3, 4.0), 3.4, 3.3, boxstyle="round,pad=0.2",
                        facecolor='#3fb95008', edgecolor='#3fb950', linewidth=1.5, linestyle='--')
ax.add_patch(grp2)
ax.text(8, 7.05, 'Proxy Server (Node.js)', color='#3fb950', fontsize=13, fontweight='bold', ha='center')

draw_box(ax, 6.6, 5.8, 2.8, 0.8, 'Session Manager', 'persistent context', '#3fb950')
draw_box(ax, 6.6, 4.5, 2.8, 0.8, 'Training Logger', 'JSONL export', '#3fb950')

# LLM Brain group
grp3 = FancyBboxPatch((10.3, 4.0), 5.2, 3.3, boxstyle="round,pad=0.2",
                        facecolor='#d2992208', edgecolor='#d29922', linewidth=1.5, linestyle='--')
ax.add_patch(grp3)
ax.text(12.9, 7.05, 'LLM Brain', color='#d29922', fontsize=13, fontweight='bold', ha='center')

draw_box(ax, 10.6, 5.8, 2.1, 0.8, 'Claude API', 'Sonnet / Opus', '#d29922')
draw_box(ax, 13.1, 5.8, 2.1, 0.8, 'Local LLM', 'Llama 3.1 8B', '#d29922')
draw_box(ax, 10.6, 4.5, 4.6, 0.8, 'Wiki Context', '917KB game knowledge', '#d29922')

# Training Pipeline group
grp4 = FancyBboxPatch((6.3, 1.0), 9.2, 2.3, boxstyle="round,pad=0.2",
                        facecolor='#bc8cff08', edgecolor='#bc8cff', linewidth=1.5, linestyle='--')
ax.add_patch(grp4)
ax.text(10.9, 3.05, 'Training Pipeline (Python)', color='#bc8cff', fontsize=13, fontweight='bold', ha='center')

draw_box(ax, 6.6, 1.5, 2.0, 0.8, 'Distill', 'filter + score', '#bc8cff')
draw_box(ax, 9.0, 1.5, 2.0, 0.8, 'Merge', '4 sources', '#bc8cff')
draw_box(ax, 11.4, 1.5, 2.0, 0.8, 'Fine-Tune', 'QLoRA', '#bc8cff')
draw_box(ax, 13.8, 1.5, 1.5, 0.8, 'GGUF', 'export', '#bc8cff')

# Cross-group arrows - bright colors matching source
draw_arrow(ax, 2.85, 5.0, 6.6, 6.2, '#79c0ff', 'game state')
draw_arrow(ax, 6.6, 5.8, 5.45, 6.2, '#56d364', 'JSON actions')
draw_arrow(ax, 9.4, 6.2, 10.6, 6.2, '#56d364')
draw_arrow(ax, 9.4, 5.8, 13.1, 5.8, '#56d364')
draw_arrow(ax, 8, 4.5, 7.6, 2.35, '#d2a8ff', 'training log')
draw_arrow(ax, 8.6, 1.9, 9.0, 1.9, '#d2a8ff')
draw_arrow(ax, 11.0, 1.9, 11.4, 1.9, '#d2a8ff')
draw_arrow(ax, 13.4, 1.9, 13.8, 1.9, '#d2a8ff')
draw_arrow(ax, 14.55, 2.35, 14.15, 5.8, '#d2a8ff')

plt.tight_layout()
plt.savefig('/home/dustin/workingdir/apk_source/osrs/docs/images/architecture.png',
            dpi=150, facecolor='#0d1117', edgecolor='none', bbox_inches='tight')
plt.close()
print("Done: architecture.png")
