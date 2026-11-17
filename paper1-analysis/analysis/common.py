"""Shared paths and loaders for the Paper 1 revision analysis.

All scripts read the summary CSVs and logs that iQuantumRevisionRunner writes to
results/revision/<experiment>/ in the repository, and write to paper1-analysis/output/.
"""
from pathlib import Path
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent      # paper1-analysis/
REPO = ROOT.parent                                    # repository root
RESULTS = REPO / "results" / "revision"               # written by iQuantumRevisionRunner
ORIGINAL = REPO / "results" / "all-runs-combined.csv"  # results of the original submission
DATASETS = REPO / "dataset" / "iquantum"              # MQT workload files Set01-Set04
OUT = ROOT / "output"
OUT_TABLES = OUT / "tables"
OUT_FIGS = OUT / "figures"
for d in (OUT, OUT_TABLES, OUT_FIGS):
    d.mkdir(parents=True, exist_ok=True)

SETS = ["Set01", "Set02", "Set03", "Set04"]
CONDITIONS = ["no_stress", "stress"]
POLICIES = ["FCFS", "Lottery", "LoadAware", "QCLAR", "QCLAAR"]

# Files used for each experiment (the E1 run was made twice with identical
# results; the second run is used here). E6 combines instances 1-10
# (10:37:48) and 11-20 (13:18:39); the 12:45:34 run is an accidental repeat of
# instances 1-10, used only to check reproducibility. E7 holds the ten
# disjoint folds (second revision).
FILES = {
    "E1": ["summary-E1-06-10-2026_09-35-19.csv"],
    "E2": ["summary-E2-06-10-2026_09-37-50.csv"],
    "E3": ["summary-E3-06-10-2026_09-38-56.csv"],
    "E4": ["summary-E4-06-10-2026_09-42-15.csv"],
    "E4b": ["summary-E4b-06-10-2026_10-20-03.csv"],
    "E5": ["summary-E5-06-10-2026_10-30-16.csv"],
    "E6": ["summary-E6-06-10-2026_10-37-48.csv", "summary-E6-06-10-2026_13-18-39.csv"],
    "E7": ["summary-E7-19-10-2026_15-13-57.csv"],
}


def raw(name):
    """Path of a summary or log file, found from the experiment tag in its name."""
    exp = name.split("-")[1]
    return RESULTS / exp / name


def logs():
    """All runner logs, sorted by name."""
    return sorted(RESULTS.glob("*/log-E*.txt"))


def load(exp):
    """Return the summary rows of one experiment with a 'set' column added."""
    df = pd.concat([pd.read_csv(raw(f)) for f in FILES[exp]], ignore_index=True)
    df["set"] = df["dataset"].str.extract(r"(Set0\d)", expand=False)
    return df


def weights(algorithm):
    """Parse 'P:a/b/g/l' into (alpha, beta, gamma, lambda)."""
    return tuple(float(x) for x in algorithm[2:].split("/"))


def full_dataset_tables():
    """Deterministic policies (E1) and seeded Lottery summaries (E1, seeds 1-5).

    Returns (det, lot):
      det: index (scenario, set, algorithm) -> avg_waiting_time, makespan, total_completed
      lot: index (scenario, set) -> wait, wait_sd, mk, mk_sd, C, wait_ci, mk_ci
           (95% CI half-width of the mean, t distribution, 4 d.f.)
    """
    from scipy import stats
    import numpy as np
    e1 = load("E1")
    det = (e1[e1.algorithm != "LotterySeeded"]
           .set_index(["scenario", "set", "algorithm"])
           [["avg_waiting_time", "makespan", "total_completed"]])
    lr = e1[e1.algorithm == "LotterySeeded"]
    g = lr.groupby(["scenario", "set"])
    lot = pd.DataFrame({
        "wait": g.avg_waiting_time.mean(), "wait_sd": g.avg_waiting_time.std(),
        "mk": g.makespan.mean(), "mk_sd": g.makespan.std(),
        "C": g.total_completed.first(), "n": g.size(),
    })
    t = stats.t.ppf(0.975, lot["n"] - 1)
    lot["wait_ci"] = t * lot.wait_sd / np.sqrt(lot.n)
    lot["mk_ci"] = t * lot.mk_sd / np.sqrt(lot.n)
    return det, lot


def value(det, lot, scenario, s, policy, metric):
    """Full-dataset value of a policy; metric is 'avg_waiting_time' or 'makespan'."""
    if policy == "Lottery":
        return lot.loc[(scenario, s), {"avg_waiting_time": "wait", "makespan": "mk"}[metric]]
    return det.loc[(scenario, s, policy), metric]
