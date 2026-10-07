#!/usr/bin/env python3
"""Draw the figures from output/summary/summary_all.csv into output/summary/figures (PDF + PNG).

The look of the figures lives in chartstyle.py. Two forms are used:
  - labelled bars for comparisons at one point: every value is written next to its bar; where one
    group of bars dwarfs the rest, those bars are cut short and marked // so the others stay comparable;
  - lines with labels at their ends for trends (data size, client count, time).

Usage: plot.py <output-dir>
"""
import glob
import os
import sys

import pandas as pd
from matplotlib.ticker import FuncFormatter, MaxNLocator

import chartstyle as cs
from chartstyle import INK, INK2, MONO, MUTED, RULE, SYSTEM, SYSTEM_NAME, BarScale
from chartstyle import tr as T

STANDARD = "4 CPUs and 8 GiB"
SPECS = ["1c512m", "1c1g", "2c2g", "2c4g", "4c8g"]
SPEC_NAME = {spec: T(name) for spec, name in {
    "1c512m": "1 CPU, 512 MiB", "1c1g": "1 CPU, 1 GiB", "2c2g": "2 CPUs, 2 GiB",
    "2c4g": "2 CPUs, 4 GiB", "4c8g": "4 CPUs, 8 GiB"}.items()}
DASH = (0, (4, 2))
MB = "MB" if cs.CHINESE else "MiB"   # the Chinese report writes sizes the everyday way
SLO_P95_MS = 50.0   # the "carries the business" target of the spec ladder (same value as summarize.py)


class Results:
    """The summary table with a compact lookup: r.get(metric, key=value, ...) -> number or None."""

    def __init__(self, summary):
        self.s = summary

    def rows(self, **filters):
        out = self.s
        for key, want in filters.items():
            out = out[out[key].isin(want)] if isinstance(want, (list, tuple)) else out[out[key] == want]
        return out

    def get(self, metric, column="median", **filters):
        rows = self.rows(metric=metric, **filters)
        return None if rows.empty or pd.isna(rows[column].iloc[0]) else float(rows[column].iloc[0])


def bar_rows(ax, x, y, rows, bar_x, pitch=0.215, factor=True, allow_break=True):
    """Rows of labelled bars on one scale. rows: (system, variant label, value formatter, value, baseline).

    Returns the y position below the last row.
    """
    top = y
    scale = BarScale([r[3] for r in rows], allow_break=allow_break)
    previous = None
    for system, variant, fmt, value, baseline in rows:
        if previous and system != previous:
            y += 0.075
        if system != previous:
            cs.chip(ax, x, y, SYSTEM[system])
            ax.text(x + 0.12, y, SYSTEM_NAME[system], ha="left", va="center", fontsize=8.4, fontweight=500)
        previous = system
        ax.text(x + bar_x - 0.07, y, T(variant), ha="right", va="center", fontsize=8, color=INK2)
        length = scale.draw(ax, x + bar_x, y, value, SYSTEM[system])
        label = ax.text(x + bar_x + length + 0.055, y, fmt(value), ha="left", va="center", fontsize=8.4, fontweight=500)
        if factor and baseline:
            ax.annotate(cs.fmt_factor(baseline / value), xy=(1, 0.5), xycoords=label, xytext=(5, 0),
                        textcoords="offset points", ha="left", va="center", fontsize=7.6, color=MUTED)
        y += pitch
    ax.plot([x + bar_x, x + bar_x], [top - 0.1, y - pitch + 0.1], color=INK, lw=0.7)   # common baseline
    return y


def panel_head(ax, x, y, title, detail=""):
    head = ax.text(x, y, T(title), ha="left", va="center", fontsize=9.6, fontweight=600)
    if detail:
        ax.annotate(detail, xy=(1, 0.5), xycoords=head, xytext=(9, -0.5), textcoords="offset points", ha="left",
                    va="center", fontsize=7.4, color=MUTED, fontfamily=MONO)
    return y + 0.36


# ---------------------------------------------------------------- retrieval

def fig_retrieval(r, out_dir):
    def v(op, target, variant):
        return r.get("latency_ms", spec="4c8g", experiment="search", scale="l", config=["na", "tuned"],
                     operation=op, target=target, variant=variant)

    if v("point", "file", "memindex") is None or v("point", "opengauss", "noidx") is None:
        return
    t = cs.fmt_time

    def six(op, pg=("btree", "B-tree"), og_label="B-tree", og_helps=True, file_label="sorted arrays"):
        return [("file", "scan", t, v(op, "file", "scan"), None),
                ("file", file_label, t, v(op, "file", "memindex"), v(op, "file", "scan")),
                ("postgres", "no index", t, v(op, "postgres", "noidx"), None),
                ("postgres", pg[1], t, v(op, "postgres", pg[0]), v(op, "postgres", "noidx")),
                ("opengauss", "no index", t, v(op, "opengauss", "noidx"), None),
                ("opengauss", og_label, t, v(op, "opengauss", "btree"), v(op, "opengauss", "noidx") if og_helps else None)]

    fig, ax = cs.canvas(7.5, 5.2)
    y = cs.header(fig, "Without an index every query reads all 12.8 million rows",
                  "Time per query. A bar marked // is cut short: it is far longer than drawn.") + 0.08
    left, right, bar_x = 0.25, 3.95, 1.5
    y1 = panel_head(ax, left, y, "Lookup by id", "id = 21249970")
    b1 = bar_rows(ax, left, y1, [("file", "scan", t, v("point", "file", "scan"), None),
                                 ("file", "sorted arrays", t, v("point", "file", "memindex"), v("point", "file", "scan")),
                                 ("postgres", "primary key", t, v("point", "postgres", "noidx"), None),
                                 ("opengauss", "primary key", t, v("point", "opengauss", "noidx"), None)], bar_x)
    y1 = panel_head(ax, right, y, "Exact match", "title = 'The Godfather'")
    b2 = bar_rows(ax, right, y1, six("exact_rare"), bar_x)
    y = max(b1, b2) + 0.2
    bar_rows(ax, left, panel_head(ax, left, y, "Prefix", "title LIKE 'Star Wars%'"), six("prefix"), bar_x)
    bar_rows(ax, right, panel_head(ax, right, y, "Substring", "title LIKE '%Godfather%'"),
             six("contains_rare", pg=("trgm", "trigram"), og_label="B-tree, unused", og_helps=False,
                 file_label="in-memory scan"), bar_x)
    cs.footer(fig, f"Median of 5 runs · VM with {STANDARD} · IMDb data · ×: times faster than the row above")
    cs.save(fig, out_dir, "fig01_retrieval")


def fig_scaling(r, out_dir):
    op = "exact_rare"
    series = [("file", "scan", "scan", "-"), ("opengauss", "noidx", "no index", "-"), ("postgres", "noidx", "no index", "-"),
              ("opengauss", "btree", "B-tree", DASH), ("postgres", "btree", "B-tree", DASH),
              ("file", "memindex", "sorted arrays", DASH)]
    xs = [r.get("rows", experiment="prep", operation="titles", variant=s) for s in ("s", "m", "l")]
    if None in xs:
        return
    fig = cs.figure(7.5, 4.4)
    top = cs.header(fig, "Scans grow with the data; index lookups barely do",
                    "Time to find a title by exact name. Both axes are logarithmic: one grid step is ten times more.")
    ax = cs.axes(fig, 0.78, top - 0.02, 4.6, 2.95)
    ends = []
    for target, variant, label, dash in series:
        ys = [r.get("latency_ms", spec="4c8g", experiment="search", scale=s, config=["na", "tuned"], operation=op,
                    target=target, variant=variant) for s in ("s", "m", "l")]
        if None in ys:
            continue
        ax.plot(xs, ys, color=SYSTEM[target], lw=1.8, linestyle=dash, marker="o", markersize=4.8,
                markerfacecolor=SYSTEM[target], markeredgecolor="#ffffff", markeredgewidth=1.0, clip_on=False, zorder=3)
        ends.append((xs[-1], ys[-1], SYSTEM[target], [(SYSTEM_NAME[target], cs.NAME_STYLE), (T(label), cs.DETAIL_STYLE),
                                                       (cs.fmt_time(ys[-1]), cs.VALUE_STYLE)]))
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlim(xs[0] * 0.8, xs[-1] * 1.25)
    ticks = [1e-4, 1e-3, 1e-2, 1e-1, 1, 10, 100, 1000]
    ax.set_ylim(2e-5, 2500)
    ax.set_yticks(ticks)
    ax.set_yticklabels([cs.fmt_tick(v) for v in ticks])
    ax.set_xticks(xs)
    ax.set_xticklabels([T("128 k rows"), T("1.3 M"), T("12.8 M")])
    ax.minorticks_off()
    ax.grid(axis="y", color=RULE, lw=0.8)
    ax.set_axisbelow(True)
    ax.tick_params(axis="x", pad=7)
    cs.end_labels(fig, ax, ends)
    cs.footer(fig, f"Median of 5 runs · VM with {STANDARD} · IMDb data · dashed: with an index")
    cs.save(fig, out_dir, "fig02_retrieval_scaling")


# ---------------------------------------------------------------- bulk update

def fig_update(r, out_dir):
    def v(rule, target, variant):
        return r.get("latency_ms", spec="4c8g", experiment="update", scale="l", config=["na", "tuned"],
                     operation=rule, target=target, variant=variant)

    rows = [("file", "full rewrite", "safe"), ("postgres", "heap", "heap"),
            ("opengauss", "heap (default)", "heap"), ("opengauss", "Ustore", "ustore")]
    if any(v(rule, t, var) is None for rule in ("broad", "narrow") for t, _, var in rows):
        return
    changed = r.get("rows_changed", spec="4c8g", experiment="update", scale="l", operation="broad", target="file", variant="safe")
    fig, ax = cs.canvas(7.5, 2.72)
    y = cs.header(fig, "Changing ten rows costs a file as much as changing a million",
                  "Replacing “T” with “Ttt” in 15.7 million person names, commit included. // marks a bar cut short.") + 0.08
    left, label_w = 0.25, 1.95
    panels, px = [], left + label_w
    for rule, title in (("broad", T("{n} rows changed").format(n=f"{changed:,.0f}")),
                        ("narrow", T("{n} rows changed").format(n="10"))):
        scale = BarScale([v(rule, t, var) for t, _, var in rows])
        panels.append((px, rule, scale))
        ax.text(px, y, title, ha="left", va="center", fontsize=9.6, fontweight=600)
        px += scale.full + 1.1
    y += 0.36
    top, previous = y, None
    for system, variant_label, variant in rows:
        if previous and system != previous:
            y += 0.075
        if system != previous:
            cs.chip(ax, left, y, SYSTEM[system])
            ax.text(left + 0.12, y, SYSTEM_NAME[system], ha="left", va="center", fontsize=8.4, fontweight=500)
        previous = system
        ax.text(left + label_w - 0.07, y, T(variant_label), ha="right", va="center", fontsize=8, color=INK2)
        for px, rule, scale in panels:
            ms = v(rule, system, variant)
            length = scale.draw(ax, px, y, ms, SYSTEM[system])
            ax.text(px + length + 0.055, y, cs.fmt_time(ms), ha="left", va="center", fontsize=8.4, fontweight=500)
        y += 0.225
    for px, _, _ in panels:
        ax.plot([px, px], [top - 0.1, y - 0.125], color=INK, lw=0.7)
    cs.footer(fig, f"Median of 3 to 5 runs, each on a fresh copy of the data · VM with {STANDARD} · IMDb data")
    cs.save(fig, out_dir, "fig03_bulk_update")


def fig_update_space(r, out_dir):
    def mb(metric, target, variant):
        return r.get(metric, spec="4c8g", experiment="update", scale="l", config="tuned", operation="broad",
                     target=target, variant=variant)

    rows = [("postgres", "heap", "heap"), ("opengauss", "heap (default)", "heap"), ("opengauss", "Ustore", "ustore")]
    if any(mb("heap_before_mb", t, var) is None for t, _, var in rows):
        return
    growth = {(t, var): mb("heap_after_mb", t, var) - mb("heap_before_mb", t, var) for t, _, var in rows}
    kept = all(abs(mb("heap_after_vacuum_mb", t, var) - mb("heap_after_mb", t, var)) < 1 for t, _, var in rows)
    share = growth[("opengauss", "ustore")] / growth[("postgres", "heap")]
    fig, ax = cs.canvas(7.5, 2.12)
    y = cs.header(fig, f"Updating in place takes {share:.0%} of the extra space a heap table needs",
                  "Growth of the 1.2 GiB people table after 1.3 million names were made longer."
                  + (" VACUUM does not shrink the file." if kept else "")) + 0.1
    bar_rows(ax, 0.25, y, [(t, label, lambda v: f"+{v:.0f} {MB}", growth[(t, var)], None) for t, label, var in rows],
             1.95, pitch=0.225, factor=False)
    cs.footer(fig, f"Median of 3 runs · VM with {STANDARD} · table size before and after the UPDATE")
    cs.save(fig, out_dir, "fig04_update_space")


# ---------------------------------------------------------------- concurrent load

def count(v):
    """Requests per second for a label or tick: 15.3k, 82k."""
    if v == 0:
        return "0"
    if cs.CHINESE:
        return f"{v / 10000:.1f}".rstrip("0").rstrip(".") + " 万" if v >= 10000 else f"{v:,.0f}"
    return f"{v / 1000:.0f}k" if v >= 10000 else f"{v / 1000:.1f}k" if v >= 1000 else f"{v:.0f}"


def trend_axes(fig, left, top, width, height):
    ax = cs.axes(fig, left, top, width, height)
    ax.grid(axis="y", color=RULE, lw=0.8)
    ax.set_axisbelow(True)
    ax.yaxis.set_major_locator(MaxNLocator(nbins=3))
    ax.tick_params(axis="x", pad=5)
    return ax


def trend_line(ax, xs, ys, color, **kw):
    ax.plot(xs, ys, color=color, lw=1.8, marker="o", markersize=4.8, markerfacecolor=color,
            markeredgecolor="#ffffff", markeredgewidth=1.0, clip_on=False, zorder=3, **kw)


def fig_load(r, out_dir, spec="4c8g"):
    d = r.rows(experiment="load", spec=spec)
    if d.empty:
        return
    clients = sorted({int(v[1:]) for v in d["variant"]})
    xs = list(range(len(clients)))

    def series(workload, metric, target, column="median"):
        return [r.get(metric, column, experiment="load", spec=spec, operation=workload, target=target, variant=f"c{c}")
                for c in clients]

    lead = {w: series(w, "throughput_ops", "postgres")[-1] / series(w, "throughput_ops", "opengauss")[-1] - 1
            for w in ("read", "mixed")}
    ahead = "PostgreSQL" if lead["read"] > 0 else "openGauss"
    if abs(lead["read"]) > 0.03 and abs(lead["mixed"]) > 0.03 and (lead["read"] > 0) == (lead["mixed"] > 0):
        title = (f"At {clients[-1]} clients {ahead} serves {abs(lead['read']):.0%} more reads "
                 f"and {abs(lead['mixed']):.0%} more mixed requests")
    else:
        title = "The two systems stay close under concurrent load"
    fig = cs.figure(7.5, 5.12)
    top = cs.header(fig, title, "Full data set. Lines: median of the rounds; shading: lowest to highest round.")
    ov = cs.overlay(fig)
    cs.key(ov, 0.25, top + 0.02, ["postgres", "opengauss"])
    columns = [("read", "Reads"), ("mixed", "80 % reads, 20 % writes")]
    measures = [("throughput_ops", "requests per second", count, count),
                ("p95_ms", "95th percentile latency", cs.fmt_time, cs.fmt_tick)]
    for row, (metric, what, fmt, tick) in enumerate(measures):
        for col, (workload, name) in enumerate(columns):
            left, y0 = 0.72 + col * 3.6, top + 0.6 + row * 1.78
            ov.text(left - 0.47, y0 - 0.17, T(f"{name}: {what}"), ha="left", va="center", fontsize=8.8, fontweight=600)
            ax = trend_axes(fig, left, y0, 2.3, 1.2)
            ends, highest = [], 0
            for target in ("postgres", "opengauss"):
                ys = series(workload, metric, target)
                if None in ys:
                    continue
                trend_line(ax, xs, ys, SYSTEM[target])
                ax.fill_between(xs, series(workload, metric, target, "min"), series(workload, metric, target, "max"),
                                color=SYSTEM[target], alpha=0.15, lw=0)
                highest = max(highest, max(series(workload, metric, target, "max")))
                ends.append((xs[-1], ys[-1], SYSTEM[target], [(fmt(ys[-1]), dict(fontsize=8.4, fontweight=500))]))
            ax.set_xlim(-0.2, xs[-1] + 0.2)
            ax.set_ylim(0, highest * 1.08)
            ax.set_xticks(xs)
            ax.set_xticklabels([str(c) for c in clients[:-1]] + [T("{n} clients").format(n=clients[-1])])
            ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _, f=tick: "0" if v == 0 else f(v)))
            cs.end_labels(fig, ax, ends, min_gap_pt=11, lead=8)
    cs.footer(fig, f"VM with {SPEC_NAME[spec].replace(',', ' and')} · clients on the host · settings sized for the machine")
    cs.save(fig, out_dir, "fig05_concurrent_load")


# ---------------------------------------------------------------- server requirements

def ladder_value(r, spec, target, operation, metric, config="tuned"):
    return r.get(metric, experiment="ladder", spec=spec, target=target, config=config, operation=operation)


def ladder_status(r, spec, target, config):
    """'ok' = started, no failed request, p95 within the target, alive afterwards; 'weak' = started; 'fail'."""
    if config == "fresh":
        return "ok" if r.get("available", experiment="ladder", spec=spec, target=target, operation="fresh_install") == 1 else "fail"
    if ladder_value(r, spec, target, "start", "available", config) != 1:
        return "fail"
    errors = ladder_value(r, spec, target, "mixed", "errors", config)
    p95 = ladder_value(r, spec, target, "mixed", "p95_ms", config)
    alive = ladder_value(r, spec, target, "survive", "still_running", config)
    return "ok" if errors == 0 and p95 is not None and p95 <= SLO_P95_MS and alive == 1 else "weak"


def smallest(r, specs, target, config):
    passing = [s for s in specs if ladder_status(r, s, target, config) == "ok"]
    return SPEC_NAME[passing[0]] if passing else None


def fig_ladder(r, out_dir):
    specs = [s for s in SPECS if not r.rows(experiment="ladder", spec=s).empty]
    if not specs:
        return
    need = {t: smallest(r, specs, t, "tuned") for t in ("postgres", "opengauss")}
    if need["postgres"] and need["postgres"] == need["opengauss"]:
        title = f"Both carry the load from {need['postgres']}"
    else:
        title = "; ".join(f"{SYSTEM_NAME[t]} needs {need[t]}" if need[t] else f"{SYSTEM_NAME[t]} never meets the target"
                          for t in ("postgres", "opengauss"))
    panels = [("start", "idle_mem_mb", "Memory when idle", lambda v: f"{v:,.0f} {MB}", False),
              ("mixed", "throughput_ops", "Requests per second", lambda v: f"{v:,.0f}", True),
              ("mixed", "p95_ms", "95th percentile latency", cs.fmt_time, True)]
    fig, ax = cs.canvas(7.5, 1.85 + 0.56 * len(specs))
    y = cs.header(fig, title, "Each machine size with the full data set, 16 clients, 80 % reads and 20 % writes. "
                              "// marks a bar cut short.") + 0.08
    left, label_w, pitch = 0.25, 2.0, 1.72
    scales = []
    for i, (operation, metric, name, _, allow) in enumerate(panels):
        values = [ladder_value(r, s, t, operation, metric) for s in specs for t in ("postgres", "opengauss")]
        scales.append(BarScale([v for v in values if v is not None], short=0.36, gap=0.07, long=0.44, allow_break=allow))
        ax.text(left + label_w + i * pitch, y, T(name), ha="left", va="center", fontsize=9.2, fontweight=600)
    y += 0.34
    top = y
    for spec in specs:
        ax.text(left, y, SPEC_NAME[spec], ha="left", va="center", fontsize=8.4, fontweight=600)
        for target in ("postgres", "opengauss"):
            cs.chip(ax, left + 1.02, y, SYSTEM[target])
            ax.text(left + 1.14, y, SYSTEM_NAME[target], ha="left", va="center", fontsize=8.2, color=INK2)
            if ladder_value(r, spec, target, "start", "available") != 1:
                ax.text(left + label_w + 0.05, y, T("does not start"), ha="left", va="center", fontsize=8.2, color=MUTED)
            else:
                for i, (operation, metric, _, fmt, _) in enumerate(panels):
                    v = ladder_value(r, spec, target, operation, metric)
                    if v is None:
                        continue
                    px = left + label_w + i * pitch
                    length = scales[i].draw(ax, px, y, v, SYSTEM[target])
                    ax.text(px + length + 0.055, y, fmt(v), ha="left", va="center", fontsize=8.2, fontweight=500)
            y += 0.215
        y += 0.13
    for i in range(len(panels)):
        px = left + label_w + i * pitch
        ax.plot([px, px], [top - 0.1, y - 0.245], color=INK, lw=0.7)
    cs.footer(fig, f"Settings sized for each machine · target: no failed request and 95 % of requests within {SLO_P95_MS:.0f} ms")
    cs.save(fig, out_dir, "fig06_server_requirements")


def fig_ladder_matrix(r, out_dir):
    specs = [s for s in SPECS if not r.rows(experiment="ladder", spec=s).empty]
    if not specs:
        return
    rows = [(t, c, label) for t in ("postgres", "opengauss")
            for c, label in (("fresh", "new install"), ("default", "moved from 8 GiB as is"), ("tuned", "settings sized to fit"))]
    status = {(t, c, s): ladder_status(r, s, t, c) for t, c, _ in rows for s in specs}
    fails = {t: sum(status[(t, c, s)] == "fail" for _, c, _ in rows[:3] for s in specs) for t in ("postgres", "opengauss")}
    title = ("PostgreSQL starts on every machine; openGauss fails on %d of %d attempts" % (fails["opengauss"], 3 * len(specs))
             if fails["postgres"] == 0 and fails["opengauss"] > 0 else "Which machine sizes each system can run on")
    fig, ax = cs.canvas(7.5, 3.85)
    y = cs.header(fig, title, "Does the server start, and does it carry 16 clients on the full data set?") + 0.12
    left, label_w, col_w = 0.25, 2.55, 0.95
    for i, spec in enumerate(specs):
        ax.text(left + label_w + i * col_w, y, SPEC_NAME[spec].replace(", ", "\n"), ha="center", va="center",
                fontsize=8, color=INK2, linespacing=1.3)
    y += 0.42
    previous = None
    for target, config, label in rows:
        if previous and target != previous:
            y += 0.12
        if target != previous:
            cs.chip(ax, left, y, SYSTEM[target])
            ax.text(left + 0.12, y, SYSTEM_NAME[target], ha="left", va="center", fontsize=8.4, fontweight=500)
        previous = target
        ax.text(left + label_w - 0.55, y, T(label), ha="right", va="center", fontsize=8, color=INK2)
        for i, spec in enumerate(specs):
            mark(ax, left + label_w + i * col_w, y, status[(target, config, spec)], SYSTEM[target])
        y += 0.26
    y += 0.12
    x = left
    for state, text in (("ok", "starts and meets the target"), ("weak", "starts, misses the target"), ("fail", "does not start")):
        mark(ax, x + 0.06, y, state, INK2)
        ax.text(x + 0.2, y, T(text), ha="left", va="center", fontsize=7.8, color=INK2)
        x += 0.42 + (0.115 if cs.CHINESE else 0.062) * len(T(text))
    cs.footer(fig, f"Target: no failed request and 95 % of requests within {SLO_P95_MS:.0f} ms · new install: start-up only, no data")
    cs.save(fig, out_dir, "fig07_where_it_runs")


def mark(ax, x, y, state, color):
    """Status by shape, so it does not depend on colour: disc = ok, ring = weak, cross = fail."""
    if state == "fail":
        ax.plot(x, y, marker="x", markersize=6.5, markeredgewidth=1.6, color=color, linestyle="none")
    else:
        ax.plot(x, y, marker="o", markersize=7.5, markeredgewidth=1.5, markeredgecolor=color,
                markerfacecolor=color if state == "ok" else cs.PAPER, linestyle="none")


# ---------------------------------------------------------------- reliability

def fig_soak(r, raw, out_dir):
    d = raw[raw["experiment"] == "soak_series"]
    if d.empty:
        return
    spec = d["spec"].iloc[0]
    drift = {}
    fig = cs.figure(7.5, 4.6)
    measures = [("throughput_ops", "Requests per second", count, count), ("p99_ms", "99th percentile latency", cs.fmt_time, cs.fmt_tick)]
    axes_, ends_ = [], []
    for row, (metric, _, fmt, tick) in enumerate(measures):
        ax = trend_axes(fig, 0.72, 1.32 + row * 1.52, 4.55, 1.05)
        ends, highest = [], 0
        for target in ("postgres", "opengauss"):
            g = d[(d["target"] == target) & (d["metric"] == metric)].sort_values("rep")
            if g.empty:
                continue
            minutes = (g["rep"] + 0.5) * 0.5
            ax.plot(minutes, g["value"], color=SYSTEM[target], lw=1.8, zorder=3)
            highest = max(highest, g["value"].max())
            if metric == "throughput_ops":
                drift[target] = g["value"].iloc[-4:].mean() / g["value"].iloc[:4].mean() - 1
            ends.append((minutes.iloc[-1], g["value"].iloc[-1], SYSTEM[target],
                         [(SYSTEM_NAME[target], cs.NAME_STYLE), (T("mean ") + fmt(g["value"].mean()), cs.VALUE_STYLE)]))
        ax.set_ylim(0, highest * 1.15)
        ax.set_xlim(0, d["rep"].max() * 0.5 + 0.5)
        ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _, f=tick: "0" if v == 0 else f(v)))
        if row == 0:
            ax.set_xticklabels([])
        else:
            ax.set_xticks([2, 4, 6, 8, 10])
            ax.set_xticklabels(["2", "4", "6", "8", T("10 min")])
        axes_.append(ax)
        ends_.append(ends)
    steady = all(abs(v) < 0.1 for v in drift.values())
    top = cs.header(fig, "Both hold their throughput for ten minutes" if steady else
                    "Throughput over ten minutes: " + ", ".join(f"{SYSTEM_NAME[t]} {v:+.0%}".replace("-", "−")
                                                                for t, v in drift.items()),
                    "16 clients, 80 % reads and 20 % writes, full data set; one point per 30 seconds.")
    ov = cs.overlay(fig)
    for row, (_, name, _, _) in enumerate(measures):
        ov.text(0.25, 1.32 + row * 1.52 - 0.2, T(name), ha="left", va="center", fontsize=8.8, fontweight=600)
    for ax, ends in zip(axes_, ends_):
        cs.end_labels(fig, ax, ends)
    cs.footer(fig, f"VM with {SPEC_NAME[spec].replace(',', ' and')} · clients on the host · settings sized for the machine")
    cs.save(fig, out_dir, "fig08_sustained_load")


def fig_lost_updates(r, out_dir):
    def lost(target, variant):
        d = r.rows(experiment="lost_update", target=target, variant=variant)
        if d.empty:
            return None
        return float(d[d["metric"] == "lost"]["median"].iloc[0]) / float(d[d["metric"] == "expected"]["median"].iloc[0])

    rows = [("file", "no lock", "nolock"), ("file", "mutex", "lock"), ("file", "mutex + fsync", "lock_fsync")]
    for target in ("postgres", "opengauss"):
        rows += [(target, "read, then write", "naive"), (target, "UPDATE x = x + 1", "atomic"),
                 (target, "SELECT … FOR UPDATE", "forupdate"), (target, "repeatable read, retry", "repeatable")]
    rows = [(t, label, lost(t, var)) for t, label, var in rows if lost(t, var) is not None]
    if len(rows) < 4:
        return
    worst_db = max((v for t, _, v in rows if t != "file"), default=0)
    fig, ax = cs.canvas(7.5, 1.5 + 0.225 * len(rows) + 0.075 * len({t for t, _, _ in rows}))
    y = cs.header(fig, f"Read, then write: up to {max(v for _, _, v in rows):.0%} of concurrent updates vanish"
                  + (", in a database too" if worst_db > 0.01 else ""),
                  "8 clients add 1 to the same counter at the same time. Share of the additions that were lost.") + 0.1
    bar_rows(ax, 0.25, y, [(t, label, lambda v: T("none lost") if v == 0 else T("{p} lost").format(p=f"{v:.0%}"), v, None)
                           for t, label, v in rows],
             2.45, pitch=0.225, factor=False, allow_break=False)
    cs.footer(fig, "Median of 3 runs · file: 16,000 additions inside the VM · databases: 8,000 additions, READ COMMITTED unless stated")
    cs.save(fig, out_dir, "fig09_lost_updates")


def fig_crash(r, raw, out_dir):
    crash = raw[raw["experiment"] == "crash"]

    def values(metric, **f):
        d = crash[crash["metric"] == metric]
        for k, v in f.items():
            d = d[d[k] == v]
        return d["value"]

    acked = values("acknowledged_commits")
    if acked.empty:
        return
    damaged = (values("acknowledged_but_missing").sum() + values("partial_transactions").sum()
               + values("unbalanced_transactions").sum())

    def outcome(lost, changed):
        text = T("none") if lost == 0 else T("{n} rows gone").format(n=f"{lost:,.0f}")
        return text + (T("; update half-applied") if changed > 0 else "")

    data_rows = []
    for target, label, variant in (("file", "overwrite in place", "inplace"), ("file", "temp file, then rename", "safe"),
                                   ("postgres", "one UPDATE", "kill9"), ("opengauss", "one UPDATE", "kill9")):
        lost = values("rows_lost", target=target, operation="bulk_update", variant=variant)
        if lost.empty:
            continue
        changed = (values("rows_with_Ttt_after", target=target, operation="bulk_update", variant=variant).max()
                   - values("rows_with_Ttt_before", target=target, operation="bulk_update", variant=variant).max())
        data_rows.append((target, label, lambda n, c=changed: outcome(n, c), lost.max(), None))
    time_rows = []
    for target in ("postgres", "opengauss"):
        for operation, label in (("write_load", "killed under write load"), ("bulk_update", "killed during bulk update")):
            ready = values("time_to_ready_ms", target=target, operation=operation)
            if not ready.empty:
                time_rows.append((target, label, cs.fmt_time, ready.median(), None))

    fig, ax = cs.canvas(7.5, 2.55 + 0.225 * (len(data_rows) + len(time_rows)) + 0.075 * 4)
    y = cs.header(fig, (f"{len(acked)} kills under write load: no committed transaction lost, none left half-done" if damaged == 0
                        else f"{len(acked)} kills under write load: {damaged:,.0f} transactions damaged"),
                  f"{acked.sum():,.0f} commits had been confirmed to the clients when the servers were killed (kill -9).") + 0.1
    left, bar_x = 0.25, 2.45
    y = panel_head(ax, left, y, "Rows lost when the writer dies in the middle of a bulk update") - 0.04
    y = bar_rows(ax, left, y, data_rows, bar_x, pitch=0.225, factor=False, allow_break=False) + 0.2
    y = panel_head(ax, left, y, "Time until the database answers again") - 0.04
    bar_rows(ax, left, y, time_rows, bar_x, pitch=0.225, factor=False, allow_break=False)
    spec = crash[crash["target"] != "file"]["spec"].iloc[0]
    cs.footer(fig, f"Databases on a VM with {SPEC_NAME[spec].replace(',', ' and')} · a killed process, not a power cut · median recovery time")
    cs.save(fig, out_dir, "fig10_crash")


FIGURES = [fig_retrieval, fig_scaling, fig_update, fig_update_space, fig_load, fig_ladder, fig_ladder_matrix,
           fig_lost_updates]
RAW_FIGURES = [fig_soak, fig_crash]   # need the individual measurements, not only their medians



def main():
    root = sys.argv[1]
    out_dir = os.environ.get("FIG_OUT") or os.path.join(root, "summary", "figures")
    os.makedirs(out_dir, exist_ok=True)
    for old in glob.glob(os.path.join(out_dir, "fig*")):
        os.remove(old)
    cs.apply()
    summary = pd.read_csv(os.path.join(root, "summary", "summary_all.csv"), keep_default_na=False)
    for col in ("median", "min", "max", "mean"):
        summary[col] = pd.to_numeric(summary[col], errors="coerce")
    r = Results(summary)
    frames = [pd.read_csv(p, keep_default_na=False, dtype=str)
              for p in glob.glob(os.path.join(root, "vm-*", "raw", "reliability.csv"))]
    raw = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=summary.columns)
    if not raw.empty:
        raw["value"] = pd.to_numeric(raw["value"], errors="coerce")
        raw["rep"] = pd.to_numeric(raw["rep"], errors="coerce").fillna(0).astype(int)
    for draw in FIGURES:
        draw(r, out_dir)
    for draw in RAW_FIGURES:
        draw(r, raw, out_dir)


if __name__ == "__main__":
    main()
