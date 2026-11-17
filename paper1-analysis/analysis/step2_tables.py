"""Step 2: full-dataset tables.

Writes:
  output/tables/full_dataset_summary.csv   all policies, both metrics, Lottery mean/sd/95% CI
  output/tables/table5_wait.tex            Table 5 (average waiting time)
  output/tables/table6_makespan.tex        Table 6 (makespan)
  output/tables/table7_throughput.tex      Table 7 (throughput, C / mean makespan for Lottery)
  output/tables/table8_ablation.tex        Table 8 (component variants vs LoadAware)
  output/tables/win_tally.txt              lowest value per cell over waiting time and makespan
"""
import pandas as pd
from common import OUT_TABLES, SETS, CONDITIONS, POLICIES, load, full_dataset_tables, value

det, lot = full_dataset_tables()
MAC = {"FCFS": "\\FCFS{}", "Lottery": "\\Lottery{}", "LoadAware": "\\LoadAware{}",
       "QCLAR": "\\QCLAR{}", "QCLAAR": "\\QCLAAR{}"}

# summary CSV
rows = []
for sc in CONDITIONS:
    for s in SETS:
        for p in POLICIES:
            r = dict(scenario=sc, set=s, policy=p,
                     avg_waiting_time=value(det, lot, sc, s, p, "avg_waiting_time"),
                     makespan=value(det, lot, sc, s, p, "makespan"))
            if p == "Lottery":
                r.update(wait_sd=lot.loc[(sc, s), "wait_sd"], wait_ci95=lot.loc[(sc, s), "wait_ci"],
                         makespan_sd=lot.loc[(sc, s), "mk_sd"], makespan_ci95=lot.loc[(sc, s), "mk_ci"])
                r["throughput_e3"] = lot.loc[(sc, s), "C"] / lot.loc[(sc, s), "mk"] * 1e3
            else:
                r["throughput_e3"] = det.loc[(sc, s, p), "total_completed"] / det.loc[(sc, s, p), "makespan"] * 1e3
            rows.append(r)
summary = pd.DataFrame(rows)
summary.to_csv(OUT_TABLES / "full_dataset_summary.csv", index=False)

def fmt(x):
    return f"{x/1e6:.2f}M" if x >= 1e6 else f"{x/1e3:.2f}K"

def fmt_pm(mean, sd):
    """Mean +/- sd, both in the unit of the mean (K or M)."""
    if mean >= 1e6:
        return f"{mean/1e6:.2f}$\\pm${sd/1e6:.2f}M"
    return f"{mean/1e3:.2f}$\\pm${sd/1e3:.2f}K"

def table(metric, sdcol, label, caption):
    out = []
    for sc, sub in [("no_stress", "(a) No-stress condition"), ("stress", "(b) Stress condition")]:
        best = {s: min(value(det, lot, sc, s, p, metric) for p in POLICIES) for s in SETS}
        out.append("\\multicolumn{5}{c}{\\textit{" + sub + "}} \\\\\n\\toprule\n\\textbf{Algorithm} & "
                   "\\textbf{Set01} & \\textbf{Set02} & \\textbf{Set03} & \\textbf{Set04} \\\\\n\\midrule")
        for p in POLICIES:
            cells = []
            for s in SETS:
                x = value(det, lot, sc, s, p, metric)
                c = fmt(x) if p != "Lottery" else fmt_pm(x, lot.loc[(sc, s), sdcol])
                if abs(x - best[s]) < 1e-9:
                    c = "\\textbf{" + c + "}"
                cells.append(c)
            out.append(MAC[p] + " & " + " & ".join(cells) + " \\\\")
        out.append("\\bottomrule")
        if sc == "no_stress":
            out.append("\\addlinespace[1.2ex]")
    return ("\\begin{table}[htbp]\n\\centering\n\\caption{" + caption + "}\n\\label{" + label + "}\n\\small\n"
            "\\begin{tabular}{lrrrr}\n" + "\n".join(out) + "\n\\end{tabular}\n\\end{table}\n")

cap = ("{m} ($\\mathrm{{K}} = 10^{{3}}$, $\\mathrm{{M}} = 10^{{6}}$). \\Lottery{{}} values are mean $\\pm$ "
       "sample standard deviation over five seeded runs (seeds 1--5); the other policies are deterministic. "
       "Best value per column is shown in bold.")
(OUT_TABLES / "table5_wait.tex").write_text(table("avg_waiting_time", "wait_sd", "tab:wait", cap.format(m="Average waiting time")))
(OUT_TABLES / "table6_makespan.tex").write_text(table("makespan", "mk_sd", "tab:makespan", cap.format(m="Makespan")))

# throughput
out = []
for sc, sub in [("no_stress", "(a) No-stress condition"), ("stress", "(b) Stress condition")]:
    tp = {p: {s: summary[(summary.scenario == sc) & (summary.set == s) & (summary.policy == p)].throughput_e3.iloc[0]
              for s in SETS} for p in POLICIES}
    out.append("\\multicolumn{5}{c}{\\textit{" + sub + "}} \\\\\n\\toprule\n\\textbf{Algorithm} & "
               "\\textbf{Set01} & \\textbf{Set02} & \\textbf{Set03} & \\textbf{Set04} \\\\\n\\midrule")
    for p in POLICIES:
        cells = []
        for s in SETS:
            c = f"{tp[p][s]:.3f}"
            if abs(tp[p][s] - max(tp[q][s] for q in POLICIES)) < 1e-12:
                c = "\\textbf{" + c + "}"
            cells.append(c)
        out.append(MAC[p] + " & " + " & ".join(cells) + " \\\\")
    out.append("\\bottomrule")
    if sc == "no_stress":
        out.append("\\addlinespace[1.2ex]")
(OUT_TABLES / "table7_throughput.tex").write_text(
    "\\begin{table}[htbp]\n\\centering\n\\caption{Throughput ($\\times 10^{-3}$ tasks per unit time), completed tasks "
    "divided by makespan at full precision; for \\Lottery{} the makespan is the mean over seeds 1--5.}\n"
    "\\label{tab:throughput}\n\\small\n\\begin{tabular}{lrrrr}\n" + "\n".join(out) + "\n\\end{tabular}\n\\end{table}\n")

# ablation (E3) relative to LoadAware
e3 = load("E3"); e1 = load("E1")
both = pd.concat([e3, e1[e1.algorithm == "QCLAR"]])
la = det.xs("LoadAware", level="algorithm")
names = [("P:1/0/0/0", "Load only $(1, 0, 0)$"), ("P:0.45/0.40/0/0", "Load, burden $(0.45, 0.40, 0)$"),
         ("P:0.45/0/0.15/0", "Load, capability $(0.45, 0, 0.15)$"), ("P:0/0.40/0.15/0", "Burden, capability $(0, 0.40, 0.15)$"),
         ("P:0/1/0/0", "Burden only $(0, 1, 0)$"), ("P:0/0/1/0", "Capability only $(0, 0, 1)$"),
         ("QCLAR", "\\QCLAR{} $(0.45, 0.40, 0.15)$"), ("MCT", "\\MCT{}, $\\sigma = 0$")]
lines, csvrows = [], []
for alg, lab in names:
    cells = []
    for sc in CONDITIONS:
        for s in SETS:
            x = both[(both.algorithm == alg) & (both.scenario == sc) & (both.set == s)].iloc[0]
            for m in ["avg_waiting_time", "makespan"]:
                r = 100 * (x[m] / la.loc[(sc, s), m] - 1)
                csvrows.append(dict(variant=alg, scenario=sc, set=s, metric=m, pct_vs_LoadAware=r))
                if s in ("Set03", "Set04"):
                    cells.append(f"{r:+.1f}".replace("+0.0", "0.0").replace("-0.0", "0.0").replace("-", "$-$"))
    if alg != "MCT":
        lines.append(lab + " & " + " & ".join(cells) + " \\\\")
pd.DataFrame(csvrows).to_csv(OUT_TABLES / "ablation_vs_LoadAware_all_cells.csv", index=False)
(OUT_TABLES / "table8_ablation.tex").write_text(
    "\\begin{table}[htbp]\n\\centering\n\\caption{Component variants: percentage change relative to \\LoadAware{} "
    "in average waiting time (W) and makespan (M).}\n\\label{tab:ablation}\n\\footnotesize\n"
    "\\begin{tabular}{lrrrrrrrr}\n\\toprule\n & \\multicolumn{4}{c}{\\textbf{No stress}} & \\multicolumn{4}{c}{\\textbf{Stress}} \\\\\n"
    " & \\multicolumn{2}{c}{Set03} & \\multicolumn{2}{c}{Set04} & \\multicolumn{2}{c}{Set03} & \\multicolumn{2}{c}{Set04} \\\\\n"
    "\\textbf{Variant} & W & M & W & M & W & M & W & M \\\\\n\\midrule\n" + "\n".join(lines) +
    "\n\\bottomrule\n\\end{tabular}\n\\end{table}\n")

# win tally over waiting time and makespan
tally, txt = {}, []
for sc in CONDITIONS:
    for s in SETS:
        for m in ["avg_waiting_time", "makespan"]:
            vals = {p: value(det, lot, sc, s, p, m) for p in POLICIES}
            best = min(vals.values())
            w = [p for p, v in vals.items() if abs(v - best) < 1e-9]
            key = "QCLAR/QCLAAR" if set(w) & {"QCLAR", "QCLAAR"} else w[0]
            tally[key] = tally.get(key, 0) + 1
            txt.append(f"{sc:9s} {s} {m:16s} lowest: {', '.join(w)}")
txt.append(f"tally over 16 comparisons: {tally}")
(OUT_TABLES / "win_tally.txt").write_text("\n".join(txt) + "\n")
print("\n".join(txt[-1:]))
print("tables written to", OUT_TABLES)
