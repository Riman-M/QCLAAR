"""Step 6: workload statistics (Table 2) and the work added by the stress transform.

Reads the four MQT workload files in dataset/iquantum and writes
  output/tables/table2_workload.csv   tasks, qubit counts, depth median [IQR], depth range
                                      (quartiles by linear interpolation, truncated to whole layers)
  output/stress_work_ratio.txt        sum(L*S) under stress / sum(L*S) without stress

The stress transform is the one in iQuantumRevisionRunner: by task index i,
i mod 10 in 0-4 halves depth and shots (floors 10 and 500, integer division),
5-7 leaves the task unchanged, 8-9 triples both.
"""
import numpy as np
import pandas as pd
from common import DATASETS, OUT, OUT_TABLES, SETS

DATA = DATASETS


def stressed(depth, shots):
    i = np.arange(len(depth)) % 10
    L = np.where(i < 5, np.maximum(10, depth // 2), np.where(i < 8, depth, depth * 3))
    S = np.where(i < 5, np.maximum(500, shots // 2), np.where(i < 8, shots, shots * 3))
    return L, S


rows, lines = [], []
for s in SETS:
    f = next(DATA.glob(f"MQT-{s}-*.csv"))
    d = pd.read_csv(f, usecols=["n_qubits", "qc_depth", "n_shots"])
    L = d.qc_depth.to_numpy(np.int64)
    S = d.n_shots.to_numpy(np.int64)
    q = np.percentile(L, [25, 50, 75])
    rows.append({"set": s, "tasks": len(d),
                 "qubits": ", ".join(str(x) for x in sorted(d.n_qubits.unique())),
                 "depth_median": int(np.median(L)), "depth_q1": int(q[0]),
                 "depth_q3": int(q[2]), "depth_min": int(L.min()), "depth_max": int(L.max())})
    Ls, Ss = stressed(L, S)
    ratio = (Ls * Ss).sum() / (L * S).sum()
    lines.append(f"{s}: total work sum(L*S) under stress / without stress = {ratio:.2f}")

t = pd.DataFrame(rows)
t.to_csv(OUT_TABLES / "table2_workload.csv", index=False)
print(t.to_string(index=False))
(OUT / "stress_work_ratio.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
