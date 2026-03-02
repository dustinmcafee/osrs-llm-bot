import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import numpy as np

np.random.seed(42)

fig, axes = plt.subplots(1, 3, figsize=(18, 6))
fig.patch.set_facecolor('#0d1117')

for ax in axes:
    ax.set_facecolor('#161b22')
    ax.set_xlim(0, 800)
    ax.set_ylim(0, 600)
    ax.invert_yaxis()
    ax.set_aspect('equal')
    for spine in ax.spines.values():
        spine.set_color('#30363d')

ax = axes[0]
ax.set_title('Straight Line (Detectable)', color='#f85149', fontsize=14, fontweight='bold', pad=10)
starts = [(100, 500), (200, 100), (700, 400)]
ends = [(650, 80), (600, 450), (150, 300)]
for (sx, sy), (ex, ey) in zip(starts, ends):
    ax.plot([sx, ex], [sy, ey], color='#f85149', linewidth=2, alpha=0.8)
    ax.plot(sx, sy, 'o', color='#f85149', markersize=8, zorder=5)
    ax.plot(ex, ey, 's', color='#f85149', markersize=10, zorder=5)
ax.text(400, 560, 'Perfectly straight = instant ban', color='#8b949e', ha='center', fontsize=10, style='italic')

ax = axes[1]
ax.set_title('Bezier Curves (Human-like)', color='#58a6ff', fontsize=14, fontweight='bold', pad=10)

def bezier_curve(p0, p1, p2, p3, steps=80):
    t = np.linspace(0, 1, steps)
    x = (1-t)**3*p0[0] + 3*(1-t)**2*t*p1[0] + 3*(1-t)*t**2*p2[0] + t**3*p3[0]
    y = (1-t)**3*p0[1] + 3*(1-t)**2*t*p1[1] + 3*(1-t)*t**2*p2[1] + t**3*p3[1]
    return x, y

paths_data = [((100, 500), (650, 80)), ((200, 100), (600, 450)), ((700, 400), (150, 300))]
for (sx, sy), (ex, ey) in paths_data:
    dx, dy = ex - sx, ey - sy
    dist = np.sqrt(dx**2 + dy**2)
    perp_x, perp_y = -dy/dist, dx/dist
    off1 = np.random.uniform(-0.3, 0.3) * dist
    off2 = np.random.uniform(-0.3, 0.3) * dist
    cp1 = (sx + dx*0.33 + perp_x*off1, sy + dy*0.33 + perp_y*off1)
    cp2 = (sx + dx*0.66 + perp_x*off2, sy + dy*0.66 + perp_y*off2)
    bx, by = bezier_curve((sx, sy), cp1, cp2, (ex, ey))
    bx += np.random.normal(0, 1.2, len(bx))
    by += np.random.normal(0, 1.2, len(by))
    ax.plot(bx, by, color='#58a6ff', linewidth=2, alpha=0.8)
    ax.plot(sx, sy, 'o', color='#58a6ff', markersize=8, zorder=5)
    ax.plot(ex, ey, 's', color='#58a6ff', markersize=10, zorder=5)
    ax.plot(*cp1, '+', color='#c9d1d9', markersize=8, alpha=0.6)
    ax.plot(*cp2, '+', color='#c9d1d9', markersize=8, alpha=0.6)
ax.text(400, 560, 'Randomized control points + Gaussian tremor', color='#8b949e', ha='center', fontsize=10, style='italic')

ax = axes[2]
ax.set_title('Overshoot + Correction (15% chance)', color='#3fb950', fontsize=14, fontweight='bold', pad=10)
sx, sy = 120, 480
ex, ey = 620, 120
dx, dy = ex - sx, ey - sy
dist = np.sqrt(dx**2 + dy**2)
perp_x, perp_y = -dy/dist, dx/dist
overshoot_dist = 35
ox = ex + (dx/dist) * overshoot_dist + np.random.uniform(-5, 5)
oy = ey + (dy/dist) * overshoot_dist + np.random.uniform(-5, 5)
off1 = 0.25 * dist
off2 = -0.15 * dist
cp1 = (sx + dx*0.33 + perp_x*off1, sy + dy*0.33 + perp_y*off1)
cp2 = (sx + dx*0.66 + perp_x*off2, sy + dy*0.66 + perp_y*off2)
bx, by = bezier_curve((sx, sy), cp1, cp2, (ox, oy), steps=60)
bx += np.random.normal(0, 1.0, len(bx))
by += np.random.normal(0, 1.0, len(by))
cx, cy = bezier_curve((ox, oy), (ox - 5, oy + 3), (ex + 3, ey - 2), (ex, ey), steps=15)
cx += np.random.normal(0, 0.8, len(cx))
cy += np.random.normal(0, 0.8, len(cy))
ax.plot(bx, by, color='#3fb950', linewidth=2, alpha=0.8)
ax.plot(cx, cy, color='#d29922', linewidth=2, alpha=0.8, linestyle='--')
ax.plot(sx, sy, 'o', color='#3fb950', markersize=8, zorder=5)
ax.plot(ex, ey, 's', color='#3fb950', markersize=10, zorder=5)
ax.plot(ox, oy, 'x', color='#d29922', markersize=12, markeredgewidth=2, zorder=5)
ax.annotate('overshoot', xy=(ox, oy), xytext=(ox+40, oy+40), color='#d29922', fontsize=10,
            arrowprops=dict(arrowstyle='->', color='#d29922', lw=1.5))
ax.annotate('correction', xy=(ex, ey), xytext=(ex+60, ey+50), color='#d29922', fontsize=10,
            arrowprops=dict(arrowstyle='->', color='#d29922', lw=1.5))
sx2, sy2 = 600, 500
ex2, ey2 = 200, 250
dx2, dy2 = ex2-sx2, ey2-sy2
dist2 = np.sqrt(dx2**2 + dy2**2)
perp_x2, perp_y2 = -dy2/dist2, dx2/dist2
cp1b = (sx2 + dx2*0.33 + perp_x2*80, sy2 + dy2*0.33 + perp_y2*80)
cp2b = (sx2 + dx2*0.66 - perp_x2*60, sy2 + dy2*0.66 - perp_y2*60)
bx2, by2 = bezier_curve((sx2, sy2), cp1b, cp2b, (ex2, ey2), steps=70)
bx2 += np.random.normal(0, 1.0, len(bx2))
by2 += np.random.normal(0, 1.0, len(by2))
ax.plot(bx2, by2, color='#3fb950', linewidth=2, alpha=0.5)
ax.plot(sx2, sy2, 'o', color='#3fb950', markersize=8, alpha=0.5, zorder=5)
ax.plot(ex2, ey2, 's', color='#3fb950', markersize=10, alpha=0.5, zorder=5)
ax.text(400, 560, 'Mimics real hand movement imprecision', color='#8b949e', ha='center', fontsize=10, style='italic')

fig.text(0.5, 0.02, 'All input dispatched via java.awt.EventQueue \u2192 Canvas  |  \u25CB = start   \u25A0 = click target   + = Bezier control point',
         ha='center', color='#c9d1d9', fontsize=11)
plt.suptitle('Mouse Movement Humanization', color='#e6edf3', fontsize=18, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0.05, 1, 0.93])
plt.savefig('/home/dustin/workingdir/apk_source/osrs/docs/images/mouse-humanization.png',
            dpi=150, facecolor='#0d1117', edgecolor='none', bbox_inches='tight')
plt.close()
print("Done: mouse-humanization.png")
