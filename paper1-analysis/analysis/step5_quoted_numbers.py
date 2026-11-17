"""Step 5: numbers quoted in the text of Sections 5.2-5.10, recomputed.

Writes output/quoted_numbers.txt. Run after step3 (uses E6_paired_tests.csv).
"""
import numpy as np
import pandas as pd
from common import OUT, OUT_TABLES, SETS, CONDITIONS, POLICIES, load, full_dataset_tables, value, weights

det, lot = full_dataset_tables()
lines = []
def say(s=""):
    print(s); lines.append(s)

say("== Full datasets: % change relative to LoadAware (Sections 5.2-5.3)")
for m in ["avg_waiting_time", "makespan"]:
    for sc in CONDITIONS:
        for s in SETS:
            base = value(det, lot, sc, s, "LoadAware", m)
            parts = [f"{p}:{100*(value(det, lot, sc, s, p, m)/base-1):+.1f}" for p in POLICIES if p != "LoadAware"]
            best_det = min(value(det, lot, sc, s, p, m) for p in ["LoadAware", "QCLAR", "QCLAAR"])
            ci = lot.loc[(sc, s), "wait_ci" if m == "avg_waiting_time" else "mk_ci"] / value(det, lot, sc, s, "Lottery", m)
            say(f"{m[:8]} {sc:9s} {s}  " + " ".join(parts) +
                f"  | QCLAAR vs QCLAR {100*(value(det,lot,sc,s,'QCLAAR',m)/value(det,lot,sc,s,'QCLAR',m)-1):+.1f}"
                f"  | Lottery vs best deterministic {100*(value(det,lot,sc,s,'Lottery',m)/best_det-1):+.1f}"
                f"  | Lottery 95% CI half-width {100*ci:.1f}% of mean")
fcfs = [value(det, lot, sc, s, "FCFS", "avg_waiting_time") / min(value(det, lot, sc, s, p, "avg_waiting_time") for p in POLICIES)
        for sc in CONDITIONS for s in SETS]
say(f"FCFS waiting time / best policy: {min(fcfs):.1f} to {max(fcfs):.1f}")

la = det.xs("LoadAware", level="algorithm")
say("\n== Weight slice E4b (Section 5.8): % vs LoadAware by alpha")
S = load("E4b"); S["alpha"] = S.algorithm.map(lambda a: weights(a)[0])
for m in ["avg_waiting_time", "makespan"]:
    S["r"] = [100 * (row[m] / la.loc[(row.scenario, row.set), m] - 1) for _, row in S.iterrows()]
    say(f"-- {m}")
    say((S.pivot_table(index="alpha", columns=["scenario", "set"], values="r").round(1)).to_string())

say("\n== Weight grid E4 (Section 5.8)")
G = load("E4"); w = G.algorithm.map(weights)
G["a"], G["b"], G["c"] = [w.map(lambda t: t[i]) for i in range(3)]
for m in ["avg_waiting_time", "makespan"]:
    G["r_" + m] = [row[m] / la.loc[(row.scenario, row.set), m] - 1 for _, row in G.iterrows()]
above = G[(G.a > G.b + G.c + 1e-9) & G.set.isin(["Set03", "Set04"])]
say(f"Set03/Set04, alpha > beta + gamma: best waiting {100*above.r_avg_waiting_time.min():+.1f}%, best makespan {100*above.r_makespan.min():+.1f}%")
g3 = G[(G.scenario == "no_stress") & (G.set == "Set03") & (G.a < G.b + G.c - 1e-9)]
say("Set03 no stress, below boundary, waiting range by gamma (%):")
say((100 * g3.groupby("c").r_avg_waiting_time.agg(["min", "max"])).round(0).to_string())
qm = det.loc[("no_stress", "Set03", "QCLAR"), "makespan"]
g = G[(G.scenario == "no_stress") & (G.set == "Set03")]
say("Set03 no stress, grid settings within 10% of QCLAR makespan: " + ", ".join(g[g.makespan <= 1.10 * qm].algorithm))

say("\n== MCT vs QCLAR on full datasets (Section 5.9): % change, mean [min, max] over seeds")
E5, E3 = load("E5"), load("E3")
q = det.xs("QCLAR", level="algorithm"); m0 = E3[E3.algorithm == "MCT"].set_index(["scenario", "set"])
for sc in CONDITIONS:
    for s in SETS:
        for m in ["avg_waiting_time", "makespan"]:
            parts = [f"exact {100*(m0.loc[(sc,s),m]/q.loc[(sc,s),m]-1):+.1f}"]
            for sg in [0.1, 0.25, 0.5, 1.0]:
                v = 100 * (E5[(E5.scenario == sc) & (E5.set == s) & np.isclose(E5.mct_sigma, sg)][m] / q.loc[(sc, s), m] - 1)
                parts.append(f"s{sg} {v.mean():+.1f} [{v.min():+.1f},{v.max():+.1f}]")
            say(f"{sc:9s} {s} {m[:8]}  " + "  ".join(parts))

say("\n== Resampled instances (Section 5.10): summary per comparison")
T = pd.read_csv(OUT_TABLES / "E6_paired_tests.csv")
for c, g in T.groupby("comparison", sort=False):
    say(f"{c:22s} mean % range {100*g.mean_ratio_minus1.min():+.1f} to {100*g.mean_ratio_minus1.max():+.1f}, "
        f"significant {int(g['significant_0.05'].sum())}/{len(g)}")
say(f"total significant after Holm: {int(T['significant_0.05'].sum())}/{len(T)}")

(OUT / "quoted_numbers.txt").write_text("\n".join(lines) + "\n")
