---

## Broker-Level Scheduling for Hybrid Quantum Edge-Cloud Systems (this fork)

This fork extends iQuantum with two broker-level scheduling policies —
**QCLAR** (Quantum Capability and Load-Aware Routing) and **QCLAAR**
(Quantum Capability, Load, and Aging-Aware Routing) — evaluated against
FCFS, Lottery, and LoadAware baselines across four MQT Bench workloads under
no-stress and stress conditions, using edge-first routing with cloud
fallback.

### What's new relative to upstream

**Scheduling policies** (`modules/iquantum/src/main/java/org/iquantum/brokers/`)
- `QEdgeBrokerQCLAR.java`, `QCloudBrokerQCLAR.java` — composite score
  combining predicted load, execution burden, and backend capability
- `QEdgeBrokerQCLAAR.java`, `QCloudBrokerQCLAAR.java` — QCLAR extended with
  an aging-inspired queue-pressure term
- `QEdgeBrokerFCFS.java`, `QCloudBrokerFCFS.java`,
  `QEdgeBrokerLoadAware.java`, `QCloudBrokerLoadAware.java` — baseline
  policies

**Experiment driver** (`modules/iquantum-examples/src/main/java/org/iquantum/examples/hybrid/`)
- `iQuantumHybridMultiDatasetRunner.java` — runs all five policies across
  all four datasets under both stress conditions and writes summary CSVs
- `iQuantumHybridExample4{FCFS,LoadAware,QCLAR,QCLAAR,AllAlgorithms}.java` —
  single-policy example runners

**Workloads** (`dataset/iquantum/`)
- `MQT-Set01-298-10-27-IBMQ27-Opt3-Extra.csv` — 298 tasks, 27-qubit topology
- `MQT-Set02-10-27-Mapped-AllAlgorithmLeft-Extra.csv` — 1133 tasks, 27-qubit topology
- `MQT-Set03-7-127-AllOpt-IBMMapped-Only127-Extra.csv` — 5818 tasks, 127-qubit topology (cloud-only)
- `MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv` — 7315 tasks (Set03's 5818 cloud tasks + 1497 additional edge-feasible tasks)

**Results** (`results/`)
- Ten raw simulation output CSVs — five independent runs each for the
  no-stress and stress conditions — plus `all-runs-combined.csv`, all 200
  rows combined for analysis.

**Paper** (`paper/`)
- `main.tex`, `references.bib` — Elsevier `elsarticle` manuscript source
- `figures/` — all figures referenced by the manuscript

### Known limitation, reported honestly in the paper

QCLAAR's aging-inspired queue-pressure term
(`normalizeAging()` in `QEdgeBrokerQCLAAR.java` / `QCloudBrokerQCLAAR.java`)
measures current waiting-list *length*, min-max normalised across candidate
backends. This is inactive by construction whenever candidate queue lengths
are tied — the common case on lightly loaded workloads — and shows an
inconsistent effect on makespan under stress on the larger workloads. This
is documented and analysed in the paper (Section 5.6) rather than fixed in
this version; a revised aging mechanism is planned as follow-up work.

### Reproducing the results

```bash
mvn clean install
# Run the full experiment suite (edit stress flag inside the runner as needed)
java -cp modules/iquantum/target/classes:modules/iquantum-examples/target/classes \
     org.iquantum.examples.hybrid.iQuantumHybridMultiDatasetRunner
```

Output CSVs land in `output/`; compare against `results/` for the exact runs
reported in the paper.

### Citation

<!-- TODO: add full citation once accepted -->
Manuscript in preparation. Citation details will be added here upon
acceptance.
