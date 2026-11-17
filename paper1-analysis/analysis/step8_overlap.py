"""Step 8: overlap of the E6 subsamples and partition check of the E7 folds.

Reimplements java.util.Random and Collections.shuffle exactly, so the task sets
drawn by iQuantumRevisionRunner can be rebuilt from the workload files without
running the simulator. Checks that the rebuilt sizes match the summary CSVs and
reports:
  - the share of tasks any two E6 subsamples have in common (expected 0.8),
  - how many of the 20 subsamples each task appears in,
  - the effective number of independent workloads, 20 / (1 + 19 * 0.8),
  - that the E7 folds partition each dataset (no task in two folds).

Writes output/overlap.txt
"""
import itertools
import numpy as np
import pandas as pd
from common import DATASETS, OUT, SETS, load

MASK = (1 << 48) - 1


class JavaRandom:
    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & MASK

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & MASK
        r = self.seed >> (48 - bits)
        return r - (1 << bits) if r >= (1 << (bits - 1)) else r

    def next_int(self, bound):
        if bound & (-bound) == bound:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val


def shuffle(lst, rnd):          # Collections.shuffle for a RandomAccess list
    for i in range(len(lst), 1, -1):
        j = rnd.next_int(i)
        lst[i - 1], lst[j] = lst[j], lst[i - 1]


def strata(qubits):
    s = {}
    for i, q in enumerate(qubits):
        s.setdefault(q, []).append(i)
    return [s[k] for k in sorted(s)]   # TreeMap order


def subsample(qubits, instance, frac=0.8):
    rng, keep = JavaRandom(instance), set()
    for idx in strata(qubits):
        c = list(idx)
        shuffle(c, rng)
        keep.update(c[:int(np.floor(frac * len(c) + 0.5))])   # Math.round
    return keep


def folds(qubits, k=10, seed=1):
    rng, out = JavaRandom(seed), [set() for _ in range(k)]
    for idx in strata(qubits):
        c = list(idx)
        shuffle(c, rng)
        for p, i in enumerate(c):
            out[p % k].add(i)
    return out


e6, e7 = load("E6"), load("E7")
lines = []
for s in SETS:
    f = next(DATASETS.glob(f"MQT-{s}-*.csv"))
    q = pd.read_csv(f, usecols=["n_qubits"]).n_qubits.tolist()
    K = [subsample(q, i) for i in range(1, 21)]
    obs = e6[(e6.set == s) & (e6.algorithm == "QCLAR") & (e6.scenario == "no_stress")].sort_values("instance").total_submitted.tolist()
    shared = [len(a & b) / len(a) for a, b in itertools.combinations(K, 2)]
    cnt = np.zeros(len(q))
    for k in K:
        cnt[list(k)] += 1
    F = folds(q)
    fobs = e7[(e7.set == s) & (e7.algorithm == "QCLAR") & (e7.scenario == "no_stress")].sort_values("fold").total_submitted.tolist()
    partition = sum(map(len, F)) == len(q) and len(set().union(*F)) == len(q)
    lines.append(f"{s}: N={len(q)}; E6 sizes match runner: {[len(k) for k in K] == obs}; "
                 f"shared fraction of two subsamples mean {np.mean(shared):.3f} (min {min(shared):.3f}, max {max(shared):.3f}); "
                 f"appearances per task: mean {cnt.mean():.1f}, min {int(cnt.min())}, max {int(cnt.max())}; "
                 f"E7 fold sizes match runner: {[len(x) for x in F] == fobs}; folds partition the dataset: {partition}")
lines.append(f"effective number of independent workloads among 20 subsamples (rho = 0.8): {20 / (1 + 19 * 0.8):.2f}")
(OUT / "overlap.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
