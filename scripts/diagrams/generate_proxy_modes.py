import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

fig, axes = plt.subplots(1, 3, figsize=(21, 9))
fig.patch.set_facecolor('#0d1117')
for ax in axes:
    ax.set_facecolor('#161b22')
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 10)
    ax.axis('off')

def draw_box(ax, x, y, w, h, label, color='#58a6ff', fontsize=10, sublabel=''):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.12",
                          facecolor=color + '20', edgecolor=color, linewidth=2)
    ax.add_patch(box)
    if sublabel:
        ax.text(x + w/2, y + h/2 + 0.15, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')
        ax.text(x + w/2, y + h/2 - 0.15, sublabel, ha='center', va='center',
                color='#8b949e', fontsize=8)
    else:
        ax.text(x + w/2, y + h/2, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')

def draw_arrow(ax, x1, y1, x2, y2, color='#c9d1d9', label='', dashed=False):
    astyle = "Simple,tail_width=2,head_width=9,head_length=6"
    arrow = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=astyle, color=color,
                             linewidth=1.5, linestyle='--' if dashed else '-')
    ax.add_patch(arrow)
    if label:
        mx, my = (x1+x2)/2 + 0.3, (y1+y2)/2
        ax.text(mx, my, label, ha='left', va='center', color=color, fontsize=7, style='italic')

# Panel 1: Manual
ax = axes[0]
ax.set_title('Manual Mode', color='#d29922', fontsize=16, fontweight='bold', pad=15)
draw_box(ax, 0.3, 7.5, 3.5, 1.2, 'RuneLite Plugin', '#58a6ff', 11, 'sends game state every N ticks')
draw_box(ax, 0.3, 5.5, 3.5, 1.2, 'Proxy (MANUAL_MODE=1)', '#3fb950', 10, 'writes state to file, waits')
draw_box(ax, 5.0, 7.0, 4.5, 0.8, '/tmp/bot_pending_state.txt', '#d29922', 9)
draw_box(ax, 5.0, 5.8, 4.5, 0.8, '/tmp/bot_response.txt', '#d29922', 9)
draw_box(ax, 1.5, 3.0, 7, 2.0, '', '#d29922', 12)
ax.text(5, 4.6, 'Human + Claude Code Session', ha='center', color='#d29922', fontsize=12, fontweight='bold')
ax.text(5, 4.1, 'Reads game state file', ha='center', color='#c9d1d9', fontsize=9)
ax.text(5, 3.7, 'Full codebase access + conversation context', ha='center', color='#c9d1d9', fontsize=9)
ax.text(5, 3.3, 'Can read source, fix bugs, AND respond', ha='center', color='#d29922', fontsize=9, fontweight='bold')
draw_arrow(ax, 2.0, 7.5, 2.0, 6.75, '#79c0ff', 'game state')
draw_arrow(ax, 3.8, 6.5, 5.0, 7.4, '#56d364', 'write')
draw_arrow(ax, 5.0, 6.2, 3.8, 6.0, '#56d364', 'read')
draw_arrow(ax, 7.25, 7.0, 7.25, 5.05, '#e3b341', 'human reads')
draw_arrow(ax, 7.25, 5.05, 7.25, 6.6, '#e3b341', 'human writes', True)
draw_arrow(ax, 2.0, 5.5, 2.0, 7.5, '#56d364', 'response', True)
for i, (t, c) in enumerate([('Debugging superpower', '#e3b341'), ('Can fix code mid-game', '#e3b341'),
                              ('Slow (human speed)', '#c9d1d9'), ('5-min timeout per turn', '#c9d1d9')]):
    ax.text(5, 2.3 - i*0.4, t, ha='center', color=c, fontsize=9)
ax.text(5, 0.5, 'Best for: debugging + development',
        ha='center', color='#d29922', fontsize=9, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor='#d2992215', edgecolor='#d29922'))

# Panel 2: Separate Sessions
ax = axes[1]
ax.set_title('Separate Sessions', color='#f85149', fontsize=16, fontweight='bold', pad=15)
draw_box(ax, 0.3, 7.5, 4, 1.2, 'RuneLite Plugin', '#58a6ff', 11, 'sends game state every N ticks')
draw_box(ax, 0.3, 5.3, 4, 1.5, '', '#f85149', 10)
ax.text(2.3, 6.4, 'Proxy (new session', ha='center', color='#f85149', fontsize=10, fontweight='bold')
ax.text(2.3, 6.0, 'per request)', ha='center', color='#f85149', fontsize=10, fontweight='bold')
ax.text(2.3, 5.6, 'claude -p (no --resume)', ha='center', color='#c9d1d9', fontsize=8)
for i in range(4):
    y = 7.8 - i * 1.4
    a = min(0.3 + i*0.15, 1.0)
    box = FancyBboxPatch((5.2, y - 0.3), 4.3, 0.7, boxstyle="round,pad=0.1",
                          facecolor='#f8514920', edgecolor='#f85149', linewidth=1.5, alpha=a)
    ax.add_patch(box)
    ax.text(7.35, y + 0.05, f'Claude (session {i+1})', ha='center', va='center',
            color='#e6edf3', fontsize=9, alpha=min(a+0.3, 1.0))
    ax.text(7.35, y - 0.15, 'no memory of prior turns', ha='center', va='center',
            color='#f85149', fontsize=7, alpha=min(a+0.2, 1.0))
draw_arrow(ax, 2.3, 7.5, 2.3, 6.85, '#79c0ff')
draw_arrow(ax, 4.3, 6.1, 5.2, 7.5, '#ff7b72')
draw_arrow(ax, 4.3, 5.8, 5.2, 6.1, '#ff7b72')
draw_arrow(ax, 4.3, 5.5, 5.2, 4.7, '#ff7b72')
for i, (t, c) in enumerate([('Each turn is a cold start', '#ff7b72'),
                              ('No memory of goals or failures', '#ff7b72'),
                              ('Re-sends full history (O(n\u00b2) tokens)', '#ff7b72'),
                              ('Circular behavior loops', '#ff7b72'),
                              ('Cannot self-correct from mistakes', '#c9d1d9')]):
    ax.text(5, 3.5 - i*0.4, t, ha='center', color=c, fontsize=9)
ax.text(5, 0.5, 'Best for: nothing (avoid this mode)',
        ha='center', color='#f85149', fontsize=9, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor='#f8514915', edgecolor='#f85149'))

# Panel 3: Persistent Session
ax = axes[2]
ax.set_title('Persistent Session', color='#3fb950', fontsize=16, fontweight='bold', pad=15)
draw_box(ax, 0.3, 7.5, 4, 1.2, 'RuneLite Plugin', '#58a6ff', 11, 'sends game state every N ticks')
draw_box(ax, 0.3, 5.3, 4, 1.5, '', '#3fb950', 10)
ax.text(2.3, 6.4, 'Proxy (--resume UUID)', ha='center', color='#3fb950', fontsize=10, fontweight='bold')
ax.text(2.3, 6.0, 'same session forever', ha='center', color='#3fb950', fontsize=10, fontweight='bold')
ax.text(2.3, 5.6, 'wiki injected on turn 1', ha='center', color='#c9d1d9', fontsize=8)
box = FancyBboxPatch((5.2, 4.0), 4.3, 4.5, boxstyle="round,pad=0.15",
                      facecolor='#3fb95015', edgecolor='#3fb950', linewidth=2)
ax.add_patch(box)
ax.text(7.35, 8.1, 'Claude (one session)', ha='center', color='#3fb950', fontsize=11, fontweight='bold')
for i, (t, c) in enumerate([('Turn 1: wiki + system prompt', '#c9d1d9'),
                              ('Turn 2: mine copper...', '#c9d1d9'),
                              ('Turn 3: got copper, need tin', '#c9d1d9'),
                              ('Turn 4: walking to tin rocks', '#c9d1d9'),
                              ('...', '#c9d1d9'),
                              ('Turn 47: full inv, banking', '#c9d1d9'),
                              ('Turn 48: deposit + smelt plan', '#56d364')]):
    ax.text(7.35, 7.6 - i*0.4, t, ha='center', color=c, fontsize=8)
ax.text(7.35, 4.8, 'Full history in context', ha='center', color='#3fb950', fontsize=9, fontweight='bold')
ax.text(7.35, 4.4, 'Coherent long-horizon plans', ha='center', color='#3fb950', fontsize=9, fontweight='bold')
draw_arrow(ax, 2.3, 7.5, 2.3, 6.85, '#79c0ff')
draw_arrow(ax, 4.3, 6.1, 5.2, 6.5, '#56d364')
draw_arrow(ax, 5.2, 5.8, 4.3, 5.6, '#56d364', '', True)
for i, (t, c) in enumerate([('Remembers all prior decisions', '#56d364'),
                              ('Learns from failed actions', '#56d364'),
                              ('Only sends current state (efficient)', '#56d364'),
                              ('Multi-step goal execution', '#56d364'),
                              ('Self-corrects over time', '#56d364')]):
    ax.text(2.5, 3.5 - i*0.4, t, ha='left', color=c, fontsize=9)
ax.text(5, 0.5, 'Best for: autonomous gameplay (recommended)',
        ha='center', color='#3fb950', fontsize=9, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor='#3fb95015', edgecolor='#3fb950'))

plt.suptitle('Proxy Modes Compared', color='#e6edf3', fontsize=20, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0, 1, 0.95])
plt.savefig('/home/dustin/workingdir/apk_source/osrs/docs/images/proxy-modes.png',
            dpi=150, facecolor='#0d1117', edgecolor='none', bbox_inches='tight')
plt.close()
print("Done: proxy-modes.png")
