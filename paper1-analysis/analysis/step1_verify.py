"""Step 1: verify the raw results before any analysis.

Checks:
  - every run completed all tasks;
  - E1 deterministic policies reproduce the published results;
  - the two E1 runs, and the two runs of E6 instances 1-10, are identical;
  - the 2x2 ablation identities (E2) and load-only = LoadAware (E3);
  - the decision counters in the logs: no candidate ever had a non-empty
    waiting list, and the number of scored decisions per condition.
"""
import re
import pandas as pd
from common import raw, logs, ORIGINAL, OUT, load, FILES

lines = []
def say(s=""):
    print(s); lines.append(s)

# completeness
for exp in FILES:
    df = load(exp)
    say(f"{exp}: {len(df)} rows, incomplete tasks = {int(df.total_incomplete.sum())}")

# E1 vs published (deterministic policies)
e1 = load("E1")
pub = pd.read_csv(ORIGINAL)
pub["set"] = pub["dataset"].str.extract(r"(Set0\d)", expand=False)
pm = pub.groupby(["scenario", "set", "algorithm"])[["makespan", "avg_waiting_time"]].mean().reset_index()
d = e1[e1.algorithm != "LotterySeeded"].merge(pm, on=["scenario", "set", "algorithm"], suffixes=("", "_pub"))
say(f"E1 vs published: {len(d)} cells, max |makespan diff| = {(d.makespan - d.makespan_pub).abs().max():.2e}, "
    f"max relative waiting diff = {((d.avg_waiting_time - d.avg_waiting_time_pub).abs() / d.avg_waiting_time_pub).max():.2e}")

# repeat runs identical
def identical(f1, f2):
    a, b = pd.read_csv(raw(f1)), pd.read_csv(raw(f2))
    return len(a) == len(b) and (a.makespan.values == b.makespan.values).all() and \
        (a.avg_waiting_time.values == b.avg_waiting_time.values).all()
say(f"E1 run 1 == run 2: {identical('summary-E1-06-10-2026_09-27-17.csv', 'summary-E1-06-10-2026_09-35-19.csv')}")
say(f"E6 instances 1-10, run 1 == repeat: {identical('summary-E6-06-10-2026_10-37-48.csv', 'summary-E6-06-10-2026_12-45-34.csv')}")
e7 = load("E7")
sizes = e7[(e7.algorithm == "QCLAR") & (e7.scenario == "no_stress")].groupby("set").total_submitted.agg(["sum", "min", "max", "count"])
full = load("E1")
full = full[(full.algorithm == "QCLAR") & (full.scenario == "no_stress")].set_index("set").total_submitted
for st, r in sizes.iterrows():
    say(f"E7 {st}: {int(r['count'])} folds, sizes {int(r['min'])}-{int(r['max'])}, total {int(r['sum'])} = full dataset {int(full[st])}: {int(r['sum']) == int(full[st])}")

# identities
k = ["scenario", "set"]
def g(df, alg):
    return df[df.algorithm == alg].set_index(k)[["makespan", "avg_waiting_time"]].sort_index()
e2, e3 = load("E2"), load("E3")
for x, y, label in [(g(e2, "P:0.45/0.40/0.15/0.0"), g(e1, "QCLAR"), "parametric QCLAR == QCLAR"),
                    (g(e2, "P:0.40/0.30/0.15/0.15"), g(e1, "QCLAAR"), "parametric QCLAAR == QCLAAR"),
                    (g(e2, "P:0.45/0.40/0.15/0.15"), g(e1, "QCLAR"), "QCLAR weights + queue term == QCLAR"),
                    (g(e2, "P:0.40/0.30/0.15/0.0"), g(e1, "QCLAAR"), "QCLAAR weights, no queue term == QCLAAR"),
                    (g(e3, "P:1/0/0/0"), g(e1, "LoadAware"), "load only == LoadAware")]:
    say(f"{label}: identical in {int((x == y).all(axis=1).sum())}/{len(x)} cells")

# decision counters
for f in logs():
    text = f.read_text(encoding="utf-8", errors="replace")
    c = [l for l in text.splitlines() if l.startswith("COUNTERS")]
    nz = sum(1 for l in c if re.search(r"(edge|cloud):\d+,[1-9]", l))
    say(f"{f.name}: {len(c)} counter lines, lines with a non-empty waiting list = {nz}")
text = raw("log-E2-06-10-2026_09-37-50.txt").read_text(encoding="utf-8")
tot = {}
for l in text.splitlines():
    if l.startswith("COUNTERS") and "P:0.40/0.30/0.15/0.15" in l:
        p = l.split("\t")
        e = int(p[4].split(":")[1].split(",")[0]); c = int(p[5].split(":")[1].split(",")[0])
        tot[p[1]] = tot.get(p[1], 0) + e + c
say(f"scored decisions per condition (QCLAAR weights with queue term): {tot}")

(OUT / "verification.txt").write_text("\n".join(lines) + "\n")
