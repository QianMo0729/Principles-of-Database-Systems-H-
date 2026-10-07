"""The shared look of every figure: typeface, colours, header block, number formats, export.

Design rules applied throughout (collected from the Python Graph Gallery's best-of list, the
Economist / FT house styles and the plottable table library):
  - the title states the finding, the subtitle says what is plotted, a small note gives the method;
  - values are written next to the marks, so no legend has to be decoded and no axis has to be read;
  - colour is used sparingly: one fixed colour per system, neutral greys for everything else;
  - nothing decorative: no frame, no tick marks, hairline rules only where they help the eye;
  - figures are laid out in inches at the size they are printed, exported as vector PDF and 300 dpi PNG;
  - every figure is checked for overlapping or clipped text before it is saved.
"""
import os

import matplotlib as mpl

mpl.use("Agg")  # render off-screen: same pixels on every machine, no window system needed

from matplotlib import font_manager
from matplotlib import pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Rectangle

HERE = os.path.dirname(os.path.abspath(__file__))
for _f in sorted(os.listdir(os.path.join(HERE, "fonts"))):
    if _f.endswith(".ttf"):
        font_manager.fontManager.addfont(os.path.join(HERE, "fonts", _f))

# FIG_REPORT=en or zh draws the figures for the written report: no title block or footnote inside
# the figure, because the report's own caption carries them. zh also switches the labels to Chinese.
REPORT = os.environ.get("FIG_REPORT") in ("en", "zh")
CHINESE = os.environ.get("FIG_REPORT") == "zh"
SANS = ["IBM Plex Sans", "IBM Plex Sans SC"] if CHINESE else "IBM Plex Sans"
MONO = "IBM Plex Mono"
PAPER = "#ffffff"
INK, INK2, MUTED, RULE = "#16181d", "#565b66", "#8d929c", "#e4e6ea"
SYSTEM = {"file": "#7a808c", "postgres": "#2a78d6", "opengauss": "#eb6834"}

ZH = {
    "File": "文件",
    # retrieval
    "Lookup by id": "按 id 查找", "Exact match": "精确匹配", "Prefix": "前缀匹配", "Substring": "子串匹配",
    "scan": "顺序扫描", "sorted arrays": "内存排序数组", "in-memory scan": "内存中扫描", "primary key": "主键",
    "no index": "无索引", "B-tree": "B-tree 索引", "trigram": "三元组索引", "B-tree, unused": "B-tree（用不上）",
    "128 k rows": "12.8 万行", "1.3 M": "128 万", "12.8 M": "1284 万",
    # update
    "full rewrite": "重写整个文件", "heap": "堆表", "heap (default)": "堆表（默认）", "Ustore": "Ustore",
    "{n} rows changed": "改动 {n} 行",
    # load
    "Reads: requests per second": "只读：每秒请求数", "80 % reads, 20 % writes: requests per second": "读写混合：每秒请求数",
    "Reads: 95th percentile latency": "只读：p95 延迟", "80 % reads, 20 % writes: 95th percentile latency": "读写混合：p95 延迟",
    "{n} clients": "{n} 并发",
    # server requirements
    "1 CPU, 512 MiB": "1 核 512 MB", "1 CPU, 1 GiB": "1 核 1 GB", "2 CPUs, 2 GiB": "2 核 2 GB",
    "2 CPUs, 4 GiB": "2 核 4 GB", "4 CPUs, 8 GiB": "4 核 8 GB",
    "Memory when idle": "空闲时占用内存", "Requests per second": "每秒请求数", "95th percentile latency": "p95 延迟",
    "99th percentile latency": "p99 延迟", "does not start": "无法启动",
    "new install": "全新安装", "moved from 8 GiB as is": "8 GB 上装好后原样迁来", "settings sized to fit": "按机器大小调好参数",
    "starts and meets the target": "能启动并达标", "starts, misses the target": "能启动但不达标", "does not start ": "无法启动",
    "mean ": "平均 ", "10 min": "10 分钟",
    # reliability
    "no lock": "不加锁", "mutex": "互斥锁", "mutex + fsync": "互斥锁 + fsync", "read, then write": "先读后写",
    "repeatable read, retry": "可重复读 + 重试", "none lost": "没有丢失", "{p} lost": "丢失 {p}",
    "Rows lost when the writer dies in the middle of a bulk update": "批量更新做到一半进程被杀：丢了多少行",
    "Time until the database answers again": "重启后多久能重新响应",
    "overwrite in place": "原地覆盖写", "temp file, then rename": "先写临时文件再改名", "one UPDATE": "一条 UPDATE",
    "killed under write load": "写入负载中被杀", "killed during bulk update": "批量更新中被杀",
    "none": "无", "{n} rows gone": "丢失 {n} 行", "; update half-applied": "，且更新只做了一半",
}


def tr(text):
    """The Chinese label for the report figures; the text itself in the default (English) figures."""
    return ZH.get(text, text) if CHINESE else text


SYSTEM_NAME = {"file": tr("File"), "postgres": "PostgreSQL", "opengauss": "openGauss"}
# One neutral ramp for magnitude (light = fast, dark = slow); it shares no hue with the systems.
RAMP = ["#f4f5f7", "#dfe2e7", "#bfc4cd", "#949ba8", "#676f7e", "#434a58", "#252a34"]


def apply():
    mpl.rcParams.update({
        "font.family": SANS, "font.size": 9, "text.color": INK,
        "figure.facecolor": PAPER, "axes.facecolor": PAPER, "savefig.facecolor": PAPER,
        "axes.edgecolor": RULE, "axes.labelcolor": INK2, "axes.linewidth": 0.8,
        "axes.spines.top": False, "axes.spines.right": False, "axes.spines.left": False, "axes.spines.bottom": False,
        "xtick.color": INK2, "ytick.color": INK2, "xtick.major.size": 0, "ytick.major.size": 0,
        "xtick.minor.size": 0, "ytick.minor.size": 0, "xtick.labelsize": 8.5, "ytick.labelsize": 8.5,
        "axes.grid": False, "grid.color": RULE, "grid.linewidth": 0.8,
        "legend.frameon": False, "pdf.fonttype": 42, "svg.fonttype": "none",
    })


def figure(width, height):
    """A figure of the given size in inches; all later positions are given in inches too."""
    return plt.figure(figsize=(width, height))


def axes(fig, left, top, width, height):
    """Add axes at a position measured in inches from the top-left corner of the figure."""
    fw, fh = fig.get_size_inches()
    return fig.add_axes([left / fw, 1 - (top + height) / fh, width / fw, height / fh])


def text(fig, left, top, s, size=9, color=INK, weight=400, family=SANS, ha="left", va="top", **kw):
    """Text at a position in inches from the top-left corner."""
    fw, fh = fig.get_size_inches()
    return fig.text(left / fw, 1 - top / fh, s, fontsize=size, color=color, fontweight=weight,
                    fontfamily=family, ha=ha, va=va, **kw)


def header(fig, title, subtitle, left=0.25, top=0.22):
    """Finding as the title, description as the subtitle. Returns the y position (inches) below the block."""
    lines = subtitle.count("\n") + 1
    if not REPORT:   # in the report the caption says this; the space is cropped away on saving
        text(fig, left, top, title, size=13.5, weight=600)
        text(fig, left, top + 0.30, subtitle, size=9.5, color=INK2, linespacing=1.35)
    return top + 0.30 + 0.19 * lines + 0.16


def footer(fig, note, left=0.25, bottom=0.16):
    if REPORT:
        return
    fw, fh = fig.get_size_inches()
    fig.text(left / fw, bottom / fh, note, fontsize=7.5, color=MUTED, ha="left", va="bottom", linespacing=1.4)


def fmt_time(ms):
    """A duration with the unit a person would use: 40 ns, 130 µs, 39.1 ms, 1.26 s."""
    def three(v):
        return f"{v:.0f}" if v >= 100 else f"{v:.1f}" if v >= 10 else f"{v:.2f}"
    if ms >= 1000:
        return f"{three(ms / 1000)} s"
    if ms >= 1:
        return f"{three(ms)} ms"
    if ms >= 0.001:
        return f"{three(ms * 1000)} µs"
    return f"{ms * 1e6:.0f} ns"


def fmt_tick(ms):
    """Axis-tick version of fmt_time for round values: 1 µs, 10 ms, 1 s."""
    for limit, unit, scale in ((1000, "s", 1e-3), (1, "ms", 1), (1e-3, "µs", 1e3), (0, "ns", 1e6)):
        if ms >= limit:
            return f"{ms * scale:g} {unit}"


def fmt_factor(x):
    """A ratio as "1,100×" (three significant digits at most)."""
    if x >= 1000:
        return f"{round(x, -len(str(int(x))) + 2):,.0f}×"
    return f"{x:.0f}×" if x >= 10 else f"{x:.1f}×"


def luminance(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


GAP = 4     # minimum horizontal distance between two texts on the same line, in pixels at 100 dpi
VGAP = 1.5  # minimum vertical distance between two texts that share a column


def check_text(fig):
    """Return a list of problems: texts that overlap each other or stick out of the figure."""
    fig.canvas.draw()
    renderer = fig.canvas.get_renderer()
    width, height = fig.canvas.get_width_height()
    # Tick labels beyond the axis limits exist as objects but are never drawn; leave them out.
    hidden = set()
    for ax in fig.axes:
        if not ax.axison:
            continue
        box = ax.get_window_extent(renderer)
        for label in ax.get_yticklabels():
            b = label.get_window_extent(renderer)
            if (b.y0 + b.y1) / 2 < box.y0 - 2 or (b.y0 + b.y1) / 2 > box.y1 + 2:
                hidden.add(id(label))
        for label in ax.get_xticklabels():
            b = label.get_window_extent(renderer)
            if (b.x0 + b.x1) / 2 < box.x0 - 2 or (b.x0 + b.x1) / 2 > box.x1 + 2:
                hidden.add(id(label))
    boxes = []
    for t in fig.findobj(mpl.text.Text):
        if not t.get_visible() or not t.get_text().strip() or id(t) in hidden:
            continue
        b = t.get_window_extent(renderer)
        if b.width < 1 or b.height < 1:
            continue
        boxes.append((t.get_text().replace("\n", " ")[:28], b))
    problems = []
    for name, b in boxes:
        if b.x0 < -1 or b.y0 < -1 or b.x1 > width + 1 or b.y1 > height + 1:
            problems.append(f"clipped: '{name}'")
    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            a, b = boxes[i][1], boxes[j][1]
            # Texts must keep a visible gap (GAP pixels sideways, VGAP above and below), not merely avoid touching.
            if a.x0 < b.x1 + GAP and b.x0 < a.x1 + GAP and a.y0 < b.y1 + VGAP and b.y0 < a.y1 + VGAP:
                problems.append(f"too close: '{boxes[i][0]}' and '{boxes[j][0]}'")
    return problems


def content_box(fig, pad=0.06):
    """The figure cropped top and bottom to what is drawn, at full width so every figure keeps one scale."""
    fig.canvas.draw()
    renderer = fig.canvas.get_renderer()
    boxes = [t.get_window_extent(renderer) for t in fig.texts] + [a.get_window_extent(renderer) for a in fig.artists]
    for ax in fig.axes:
        if ax.axison:
            boxes.append(ax.get_tightbbox(renderer))
        else:
            boxes += [a.get_window_extent(renderer) for a in ax.texts + ax.patches + ax.lines if a.get_visible()]
    ys = [v for b in boxes if b is not None and (b.width > 0 or b.height > 0) for v in (b.y0, b.y1)]
    width, height = fig.get_size_inches()
    return mpl.transforms.Bbox.from_extents(0, max(0, min(ys) / fig.dpi - pad), width, min(height, max(ys) / fig.dpi + pad))


def save(fig, out_dir, name):
    """Write <name>.pdf (vector, for the report) and <name>.png (300 dpi); report any text problem."""
    os.makedirs(out_dir, exist_ok=True)
    problems = check_text(fig)
    box = content_box(fig) if REPORT else None
    fig.savefig(os.path.join(out_dir, name + ".pdf"), bbox_inches=box)
    fig.savefig(os.path.join(out_dir, name + ".png"), dpi=300, bbox_inches=box)
    plt.close(fig)
    print(f"{name}: {'ok' if not problems else str(len(problems)) + ' text problem(s)'}")
    for p in problems:
        print("   ", p)
    return problems


# ---------------------------------------------------------------- building blocks shared by the figures

BAR_H = 0.086      # bar thickness, inches
MIN_BAR = 0.014    # shortest bar drawn, so that no value is invisible


def overlay(fig):
    """Transparent axes covering the whole figure, measured in inches from the top-left corner."""
    width, height = fig.get_size_inches()
    ax = fig.add_axes([0, 0, 1, 1], zorder=5)
    ax.set_xlim(0, width)
    ax.set_ylim(height, 0)
    ax.axis("off")
    ax.patch.set_alpha(0)
    return ax


def canvas(width, height):
    """A figure composed directly in inches.

    Bar figures use this: every value is written next to its bar, so no data axis is needed and
    positions stay exact at print size.
    """
    fig = figure(width, height)
    return fig, overlay(fig)


def key(ax, x, y, systems):
    """One line that names the systems next to their colour: the only legend a figure needs."""
    for system in systems:
        chip(ax, x, y, SYSTEM[system])
        ax.text(x + 0.12, y, SYSTEM_NAME[system], ha="left", va="center", fontsize=8.4, fontweight=500)
        x += 0.42 + 0.062 * len(SYSTEM_NAME[system])


def chip(ax, x, y, color, size=0.075):
    """The small coloured square that identifies a system next to its name."""
    ax.add_patch(Rectangle((x, y - size / 2), size, size, color=color, lw=0))


def break_point(values, min_ratio=3.0):
    """Decide where a group of bars gets a scale break.

    Returns the largest value still drawn to scale, or None when no break is needed. The break goes
    into the widest gap (as a ratio) between neighbouring values that leaves at least two bars to scale.
    """
    v = sorted(x for x in values if x and x > 0)
    best, cut = min_ratio, None
    for i in range(1, len(v) - 1):
        if v[i + 1] / v[i] > best:
            best, cut = v[i + 1] / v[i], v[i]
    return cut


class BarScale:
    """Value -> bar length in inches: one linear scale up to the break, a second, compressed one beyond it.

    Bars below the break can be compared with each other by length, and so can the tails of the bars
    above it. The break mark ( // ) says that the two groups are not on the same scale; the true value
    is always written at the end of the bar.
    """

    def __init__(self, values, short=0.46, gap=0.075, long=0.6, allow_break=True):
        values = [v for v in values if v is not None]
        self.cut = break_point(values) if allow_break else None
        self.top = max(values) or 1.0
        self.short, self.gap, self.long = short, gap, long
        self.full = short + gap + long

    def draw(self, ax, x, y, value, color, alpha=1.0):
        """Draw one bar starting at x and return its drawn length."""
        top = y - BAR_H / 2
        if self.cut is None or value <= self.cut:
            share = value / self.top * self.full if self.cut is None else value / self.cut * self.short
            length = max(share, MIN_BAR)
            ax.add_patch(Rectangle((x, top), length, BAR_H, color=color, alpha=alpha, lw=0))
            return length
        tail = max(self.long * value / self.top, MIN_BAR)
        ax.add_patch(Rectangle((x, top), self.short, BAR_H, color=color, alpha=alpha, lw=0))
        ax.add_patch(Rectangle((x + self.short + self.gap, top), tail, BAR_H, color=color, alpha=alpha, lw=0))
        for dx in (0.014, 0.043):   # break mark: two slashes across the gap
            ax.plot([x + self.short + dx, x + self.short + dx + 0.018], [y + 0.075, y - 0.075],
                    color=INK, lw=0.9, solid_capstyle="round")
        return self.short + self.gap + tail


def end_labels(fig, ax, ends, min_gap_pt=16, lead=20):
    """Label lines at their right-hand end instead of using a legend.

    ends: list of (x, y, colour, pieces) in data coordinates, pieces = [(text, style), ...] set one
    after the other. Labels that would collide are moved apart and joined to their line by a hairline.
    """
    fig.canvas.draw()
    to_px, inv = ax.transData.transform, fig.transFigure.inverted().transform
    ends = sorted(ends, key=lambda e: -to_px((e[0], e[1]))[1])
    placed = []
    for x, y, color, pieces in ends:
        px, py = to_px((x, y))
        ly = min(py, placed[-1] - min_gap_pt * fig.dpi / 72) if placed else py
        placed.append(ly)
        (fx0, fy0), (fx1, fy1) = inv((px + 6, py)), inv((px + 6 + lead, ly))
        fig.add_artist(Line2D([fx0, fx1], [fy0, fy1], color=color, lw=0.8))
        cursor = px + 11 + lead
        for piece, style in pieces:
            lx, lyf = inv((cursor, ly))
            t = fig.text(lx, lyf, piece, ha="left", va="center", **style)
            fig.canvas.draw()
            cursor += t.get_window_extent().width + 7


NAME_STYLE = dict(fontsize=8.6, fontweight=600)
DETAIL_STYLE = dict(fontsize=8.2, color=INK2)
VALUE_STYLE = dict(fontsize=8.2, color=MUTED)
