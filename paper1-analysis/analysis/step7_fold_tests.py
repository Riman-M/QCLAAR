"""Step 7: paired tests over the ten disjoint folds (experiment E7, minor revision).

Same statistics as step 3, with folds in place of instances. The ten folds of
a dataset share no tasks, so they are independent replicates. With ten folds the
smallest exact two-sided Wilcoxon p-value is 2/2^10, which cannot survive a
correction over 128 tests, so the Holm correction is applied within each
comparison's 16 tests. QCLAR vs LoadAware was fixed as the primary family before
E7 was run; the other seven comparisons are secondary. The Holm p-value over
all 128 tests is also reported.

Writes:
  output/tables/E7_fold_tests.csv
  output/tables/table10_folds.tex
"""
import numpy as np
import pandas as pd
from scipy.stats import wilcoxon
from common import OUT_TABLES, load


def holm(p):
    p = np.asarray(p, float)
    n = len(p)
    adj = np.empty(n)
    running = 0.0
    for i, idx in enumerate(np.argsort(p)):
        running = max(running, min(1.0, p[idx] * (n - i)))
        adj[idx] = running
    return adj


E = load("E7")
E["pol"] = E["algorithm"]
noisy = (E.algorithm == "MCT") & (E.mct_sigma > 0)
E.loc[noisy, "pol"] = "MCT_s" + E.loc[noisy, "mct_sigma"].astype(str)
lot = (E[E.pol == "LotterySeeded"].groupby(["scenario", "set", "fold"])[["makespan", "avg_waiting_time"]]
       .mean().reset_index().assign(pol="Lottery"))
E = pd.concat([E[E.pol != "LotterySeeded"], lot], ignore_index=True)

PAIRS = [("QCLAR", "LoadAware"), ("QCLAAR", "LoadAware"), ("QCLAAR", "QCLAR"), ("QCLAR", "Lottery"),
         ("QCLAR", "FCFS"), ("MCT", "QCLAR"), ("MCT_s0.25", "QCLAR"), ("MCT_s0.5", "QCLAR")]
rows = []
for (sc, st), g in E.groupby(["scenario", "set"]):
    for a, b in PAIRS:
        for m in ["avg_waiting_time", "makespan"]:
            x = g[g.pol == a].set_index("fold")[m].sort_index()
            y = g[g.pol == b].set_index("fold")[m].sort_index()
            assert (x.index == y.index).all() and len(x) == 10
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
T["p_holm_within_comparison"] = T.groupby("comparison").p.transform(holm)
T["p_holm_all128"] = holm(T.p.values)
T["significant_within_comparison_0.05"] = T.p_holm_within_comparison < 0.05
T.to_csv(OUT_TABLES / "E7_fold_tests.csv", index=False)
for c in T.comparison.unique():
    X = T[T.comparison == c]
    print(f"{c:22s} mean % range {100*X.mean_ratio_minus1.min():+.1f} to {100*X.mean_ratio_minus1.max():+.1f}, "
          f"significant within comparison {int(X['significant_within_comparison_0.05'].sum())}/16")
print(f"significant after Holm over all 128 tests: {int((T.p_holm_all128 < 0.05).sum())}")

# Table 10: QCLAR vs LoadAware on the folds
X = T[T.comparison == "QCLAR vs LoadAware"]
lines = []
for sc, lab in [("no_stress", "No stress"), ("stress", "Stress")]:
    for s in ["Set01", "Set02", "Set03", "Set04"]:
        c = [lab if s == "Set01" else "", s]
        for m in ["avg_waiting_time", "makespan"]:
            r = X[(X.scenario == sc) & (X.set == s) & (X.metric == m)].iloc[0]
            ph = r.p_holm_within_comparison
            c.append(f"{100*r.mean_ratio_minus1:+.1f} [{100*r.ci_lo:+.1f}, {100*r.ci_hi:+.1f}]".replace("-", "$-$"))
            c.append(f"{r.a_lower}/10")
            c.append(f"{ph:.3f}" if ph < 0.1 else f"{ph:.2f}")
        lines.append(" & ".join(c) + " \\\\")
    if sc == "no_stress":
        lines.append("\\midrule")
(OUT_TABLES / "table10_folds.tex").write_text(
    "\\begin{table}[htbp]\n\\centering\n\\caption{\\QCLAR{} against \\LoadAware{} over ten disjoint folds.}\n"
    "\\label{tab:folds}\n\\footnotesize\n\\begin{tabular}{llrrrrrr}\n\\toprule\n"
    " & & \\multicolumn{3}{c}{\\textbf{Average waiting time}} & \\multicolumn{3}{c}{\\textbf{Makespan}} \\\\\n"
    "\\textbf{Condition} & \\textbf{Set} & \\% [95\\% CI] & lower & $p_{\\mathrm{Holm}}$ & \\% [95\\% CI] & lower & $p_{\\mathrm{Holm}}$ \\\\\n"
    "\\midrule\n" + "\n".join(lines) + "\n\\bottomrule\n\\end{tabular}\n\\end{table}\n")
print("written", OUT_TABLES / "E7_fold_tests.csv")
