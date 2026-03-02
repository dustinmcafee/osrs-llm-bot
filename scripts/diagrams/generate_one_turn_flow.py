import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

ARROW = '#c9d1d9'

fig, ax = plt.subplots(1, 1, figsize=(16, 7))
fig.patch.set_facecolor('#0d1117')
ax.set_facecolor('#0d1117')
ax.set_xlim(0, 16)
ax.set_ylim(0, 7)
ax.axis('off')

def draw_box(ax, x, y, w, h, label, color='#58a6ff', fontsize=10, sublabel=''):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.1",
                          facecolor=color + '25', edgecolor=color, linewidth=2)
    ax.add_patch(box)
    if sublabel:
        ax.text(x + w/2, y + h/2 + 0.1, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')
        ax.text(x + w/2, y + h/2 - 0.18, sublabel, ha='center', va='center',
                color='#8b949e', fontsize=8)
    else:
        ax.text(x + w/2, y + h/2, label, ha='center', va='center',
                color='#e6edf3', fontsize=fontsize, fontweight='bold')

def draw_arrow(ax, x1, y1, x2, y2, color=ARROW, label=''):
    style = "Simple,tail_width=2,head_width=10,head_length=6"
    arrow = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style, color=color, linewidth=1.5)
    ax.add_patch(arrow)
    if label:
        mx, my = (x1+x2)/2, (y1+y2)/2 + 0.2
        ax.text(mx, my, label, ha='center', va='center', color=color, fontsize=8, style='italic')

ax.text(8, 6.6, 'One Turn: Observe \u2192 Reason \u2192 Act', color='#e6edf3', fontsize=20, fontweight='bold', ha='center')

draw_box(ax, 0.2, 3.5, 2.5, 2.2, 'OSRS Game World', '#f0883e', 12)
game_lines = ['[PLAYER] HP:17/17', '[STATUS] IDLE', '[NEARBY] Cow(x6)', '[INVENTORY] 10/28']
for i, line in enumerate(game_lines):
    ax.text(1.45, 5.25 - i*0.35, line, ha='center', va='center', color='#f0883e', fontsize=7, fontfamily='monospace')

draw_arrow(ax, 2.75, 4.6, 3.3, 4.6, '#f0883e', 'read')

draw_box(ax, 3.3, 3.8, 1.8, 1.6, 'Serialize', '#58a6ff', 11, '23 sections')
draw_arrow(ax, 5.15, 4.6, 5.7, 4.6, '#79c0ff', 'text')

draw_box(ax, 5.7, 3.3, 3.5, 2.6, '', '#3fb950', 10)
state_lines = ['[PLAYER] BotPlayer | Combat:16', '  HP:17/17 | Run:82% [ON]', '[STATUS] IDLE',
               '[SKILLS] Atk:20 Str:10 Def:8', '[INVENTORY] (10/28)', '  Cooked meat(x10)',
               '[NEARBY_NPCS]', '  Cow(lvl:2)(x6) dist:2']
ax.text(7.45, 5.7, 'Game State (plain text)', ha='center', color='#3fb950', fontsize=10, fontweight='bold')
for i, line in enumerate(state_lines):
    ax.text(5.9, 5.25 - i*0.3, line, va='center', color='#3fb950', fontsize=7, fontfamily='monospace')

draw_arrow(ax, 9.25, 4.6, 9.8, 4.6, '#56d364')

draw_box(ax, 9.8, 3.5, 2.2, 2.2, 'Claude / LLM', '#d29922', 12)
ax.text(10.9, 4.95, 'reasons about', ha='center', color='#d29922', fontsize=8)
ax.text(10.9, 4.65, 'game state...', ha='center', color='#d29922', fontsize=8)
ax.text(10.9, 4.15, 'picks optimal', ha='center', color='#d29922', fontsize=8)
ax.text(10.9, 3.85, 'actions', ha='center', color='#d29922', fontsize=8)

draw_arrow(ax, 12.05, 4.6, 12.6, 4.6, '#e3b341', 'JSON')

draw_box(ax, 12.6, 3.3, 3.2, 2.6, '', '#bc8cff', 10)
ax.text(14.2, 5.7, 'Actions (JSON array)', ha='center', color='#bc8cff', fontsize=10, fontweight='bold')
action_lines = ['[', '  {"action": "PICKUP_ITEM",', '   "name": "Bones"},',
                '  {"action": "PICKUP_ITEM",', '   "name": "Cowhide"},',
                '  {"action": "INTERACT_NPC",', '   "name": "Cow",', '   "option": "Attack"}', ']']
for i, line in enumerate(action_lines):
    ax.text(12.8, 5.35 - i*0.27, line, va='center', color='#bc8cff', fontsize=7, fontfamily='monospace')

draw_arrow(ax, 14.2, 3.3, 14.2, 2.6, '#d2a8ff')

draw_box(ax, 10.5, 1.5, 2.2, 0.8, 'Parse + Validate', '#f85149', 10, '130+ aliases')
draw_box(ax, 7.5, 1.5, 2.5, 0.8, '3-Phase Execute', '#f85149', 10, 'client\u2192bg\u2192client')
draw_box(ax, 4.5, 1.5, 2.5, 0.8, 'Human Simulator', '#f85149', 10, 'Bezier + delays')
draw_box(ax, 1.5, 1.5, 2.5, 0.8, 'Game Canvas', '#f0883e', 10, 'EventQueue dispatch')

draw_arrow(ax, 13.1, 1.9, 12.75, 1.9, '#ff7b72')
draw_arrow(ax, 10.5, 1.9, 10.05, 1.9, '#ff7b72')
draw_arrow(ax, 7.5, 1.9, 7.05, 1.9, '#ff7b72')
draw_arrow(ax, 4.5, 1.9, 4.05, 1.9, '#ff7b72')

arrow = FancyArrowPatch((1.5, 1.9), (0.5, 3.5), connectionstyle="arc3,rad=-0.4",
                         arrowstyle="Simple,tail_width=2,head_width=10,head_length=6",
                         color='#f0883e', linewidth=1.5)
ax.add_patch(arrow)
ax.text(0.15, 2.7, 'next\ntick', ha='center', color='#f0883e', fontsize=8, fontweight='bold')

ax.text(8, 0.8, 'Every 3\u201320 game ticks (1.8\u201312s)  \u2022  configurable via tickRate  \u2022  async to avoid blocking client thread',
        ha='center', color='#c9d1d9', fontsize=9, style='italic')

plt.tight_layout()
plt.savefig('/home/dustin/workingdir/apk_source/osrs/docs/images/one-turn-flow.png',
            dpi=150, facecolor='#0d1117', edgecolor='none', bbox_inches='tight')
plt.close()
print("Done: one-turn-flow.png")
