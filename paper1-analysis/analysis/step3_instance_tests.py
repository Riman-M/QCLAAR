"""Step 3: paired tests over the 20 resampled instances (experiment E6).

For each comparison A vs B, dataset, condition and metric:
  r_k = x_A,k / x_B,k - 1 on each instance k
  mean of r_k, 95% percentile bootstrap CI (10 000 resamples, seed 0),
  number of instances where A is lower,
  two-sided Wilcoxon signed-rank test (exact when no zero differences),
  matched-pairs rank-biserial correlation,
  Holm correction over all 128 tests.
Lottery's value on an instance is the mean over seeds 1-5.

Writes:
  output/tables/E6_paired_tests.csv
  output/tables/table9_instances.tex
"""
import numpy as np
import pandas as pd
from scipy.stats import wilcoxon
from common import OUT_TABLES, load

E = load("E6")
E["pol"] = E["algorithm"]
noisy = (E.algorithm == "MCT") & (E.mct_sigma > 0)
E.loc[noisy, "pol"] = "MCT_s" + E.loc[noisy, "mct_sigma"].astype(str)
lot = (E[E.pol == "LotterySeeded"].groupby(["scenario", "set", "instance"])[["makespan", "avg_waiting_time"]]
       .mean().reset_index().assign(pol="Lottery"))
E = pd.concat([E[E.pol != "LotterySeeded"], lot], ignore_index=True)

PAIRS = [("QCLAR", "LoadAware"), ("QCLAAR", "LoadAware"), ("QCLAAR", "QCLAR"), ("QCLAR", "Lottery"),
         ("QCLAR", "FCFS"), ("MCT", "QCLAR"), ("MCT_s0.25", "QCLAR"), ("MCT_s0.5", "QCLAR")]
rows = []
for (sc, st), g in E.groupby(["scenario", "set"]):
    for a, b in PAIRS:
        for m in ["avg_waiting_time", "makespan"]:
            x = g[g.pol == a].set_index("instance")[m].sort_index()
            y = g[g.pol == b].set_index("instance")[m].sort_index()
            assert (x.index == y.index).all() and len(x) == 20
            r = (x / y - 1).values
            d = x.values - y.values
            nz = np.count_nonzero(d)
            if nz == 0:
                p, rb = 1.0, 0.0
            else:
                p = wilcoxon(d, zero_method="wilcox", method="exact" if nz == len(d) else "approx").pvalue
                dd = d[d != 0]
                rk = pd.Series(np.abs(dd)).rank().values
                rb = (rk[dd > 0].sum() - rk[dd < 0].sum()) / rk.sum()
            bs = np.random.default_rng(0).choice(r, (10000, len(r))).mean(1)
            rows.append(dict(scenario=sc, set=st, comparison=f"{a} vs {b}", metric=m, n=len(r),
                             mean_ratio_minus1=r.mean(), ci_lo=np.percentile(bs, 2.5), ci_hi=np.percentile(bs, 97.5),
                             a_lower=int((d < 0).sum()), p=p, rank_biserial=rb))
T = pd.DataFrame(rows)
n = len(T)
order = np.argsort(T.p.values)
adj = np.empty(n)
running = 0.0
for i, idx in enumerate(order):
    running = max(running, min(1.0, T.p.values[idx] * (n - i)))
    adj[idx] = running
T["p_holm"] = adj
T["significant_0.05"] = T.p_holm < 0.05
T.to_csv(OUT_TABLES / "E6_paired_tests.csv", index=False)
print(f"{n} tests, {int(T['significant_0.05'].sum())} significant after Holm correction")

# Table 9: QCLAR vs LoadAware
X = T[T.comparison == "QCLAR vs LoadAware"]
lines = []
for sc, lab in [("no_stress", "No stress"), ("stress", "Stress")]:
    for s in ["Set01", "Set02", "Set03", "Set04"]:
        c = [lab if s == "Set01" else "", s]
        for m in ["avg_waiting_time", "makespan"]:
            r = X[(X.scenario == sc) & (X.set == s) & (X.metric == m)].iloc[0]
            ps = "$<$0.001" if r.p_holm < 0.001 else f"{r.p_holm:.2f}"
            c.append(f"{100*r.mean_ratio_minus1:+.1f} [{100*r.ci_lo:+.1f}, {100*r.ci_hi:+.1f}]".replace("-", "$-$"))
            c.append(f"{r.a_lower}/20")
            c.append(ps)
        lines.append(" & ".join(c) + " \\\\")
    if sc == "no_stress":
        lines.append("\\midrule")
(OUT_TABLES / "table9_instances.tex").write_text(
    "\\begin{table}[htbp]\n\\centering\n\\caption{\\QCLAR{} against \\LoadAware{} over 20 resampled instances.}\n"
    "\\label{tab:instances}\n\\footnotesize\n\\begin{tabular}{llrrrrrr}\n\\toprule\n"
    " & & \\multicolumn{3}{c}{\\textbf{Average waiting time}} & \\multicolumn{3}{c}{\\textbf{Makespan}} \\\\\n"
    "\\textbf{Condition} & \\textbf{Set} & \\% [95\\% CI] & lower & $p_{\\mathrm{Holm}}$ & \\% [95\\% CI] & lower & $p_{\\mathrm{Holm}}$ \\\\\n"
    "\\midrule\n" + "\n".join(lines) + "\n\\bottomrule\n\\end{tabular}\n\\end{table}\n")
print("written", OUT_TABLES / "E6_paired_tests.csv")
