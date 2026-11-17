# Paper 1: analysis code for the revision

These scripts reproduce every table, figure and quoted number of the revised
manuscript from the simulation output stored in this repository.

## Where the data come from
The scripts read the files already in this repository; nothing is copied.

| Repository path | Content |
|---|---|
| `results/revision/E1/` ... `E7/` | Summary CSVs and logs written by `iQuantumRevisionRunner` (the logs hold the per-decision `COUNTERS` lines) |
| `results/all-runs-combined.csv` | Results of the original submission, used to check reproduction |
| `dataset/iquantum/MQT-Set01...Set04` | The four MQT workload files |
| `modules/` | The simulator, including `iQuantumRevisionRunner` and the parametric and seeded brokers |

Experiments (Table 3 of the manuscript): E1 reproduction and seeded Lottery;
E2 2x2 ablation; E3 component variants and MCT; E4 66-point weight grid;
E4b weight slice; E5 MCT with estimate error; E6 20 resampled instances
(instances 1-10 in the 10:37:48 run, 11-20 in the 13:18:39 run; the 12:45:34
run is an accidental repeat of 1-10, used only to show that runs reproduce
exactly); E7 ten disjoint folds per dataset (second revision).

## Running
Python 3.10 or later. From this folder (`paper1-analysis`), the whole run
takes about ten seconds:

    pip install -r requirements.txt
    python run_all.py

Outputs are written to `paper1-analysis/output/`, which is not tracked.

Each step can also be run on its own from the `analysis` folder, for
example `python step3_instance_tests.py`.

| Step | Script | Produces |
|---|---|---|
| 1 | `step1_verify.py` | `output/verification.txt`: completeness, reproduction of the published values, repeat runs, ablation identities, decision counters (14,564 per condition, none with a non-empty waiting list) |
| 2 | `step2_tables.py` | Tables 5-8 as LaTeX, `full_dataset_summary.csv`, `win_tally.txt` (12 / 2 / 2) |
| 3 | `step3_instance_tests.py` | `E6_paired_tests.csv` (128 tests, Wilcoxon, bootstrap CI, rank-biserial, Holm) and Table 9 |
| 4 | `step4_figures.py` | Figures 4-11 under the file names the manuscript uses |
| 5 | `step5_quoted_numbers.py` | `output/quoted_numbers.txt`: the percentages quoted in Sections 5.2-5.10 |
| 6 | `step6_workload.py` | `table2_workload.csv` (Table 2) and `output/stress_work_ratio.txt` (stress raises total work by 2.06 to 2.59 times) |
| 7 | `step7_fold_tests.py` | `E7_fold_tests.csv` (paired tests over the ten disjoint folds; Holm within each comparison and over all 128 tests) and Table 10 |
| 8 | `step8_overlap.py` | `output/overlap.txt`: rebuilds the E6 subsamples and E7 folds from the workload files (exact reimplementation of Java's random shuffle), checks them against the runner's output, and reports the overlap (80%) and effective sample size (about 1.2) |

Figures 1-3 (framework, pipeline, execution regime) are unchanged from the
original submission and are not regenerated.

## Checks made before release
- Tables 5 to 10 produced by the scripts agree with the revised manuscript
  cell for cell, and Table 2 agrees with the workload files.
- E7: every dataset's ten folds add up to the full dataset, and the fold
  sizes match a reimplementation of the runner's shuffle (step 8).
- `E6_paired_tests.csv` gives 89 of 128 tests significant after Holm.
- The figures are the manuscript figures; Figure 10 was redrawn for
  legibility in the third revision.

## Rerunning the simulations
Set `EXPERIMENT` (and, for E6, `E6_FIRST_INSTANCE` and `E6_LAST_INSTANCE`)
at the top of `modules/iquantum-examples/src/main/java/org/iquantum/examples/hybrid/iQuantumRevisionRunner.java`
and run that class from the repository root. New summary CSVs and logs appear
under `results/revision/<experiment>/`; to analyse them, update the file names
in `analysis/common.py` (`FILES`).

## Methods in brief
- Lottery on the full datasets: mean and sample standard deviation over
  seeds 1-5; 95% confidence interval of the mean with the t distribution
  (4 degrees of freedom).
- Throughput: completed tasks divided by makespan; for Lottery, divided by
  the mean makespan over seeds.
- Instance tests: relative difference per instance, 95% percentile
  bootstrap interval of the mean (10,000 resamples, seed 0), two-sided
  Wilcoxon signed-rank test (exact without zero differences), matched-pairs
  rank-biserial correlation, Holm correction over all 128 tests. For
  Lottery the value on an instance is the mean over seeds 1-5.
- The 20 instances overlap (any two share 80% of their tasks), so their tests
  measure consistency across subsamples. The ten folds share no tasks; their
  tests use the Holm correction within each comparison's 16 tests, with
  QCLAR vs LoadAware as the primary family.

Tested with Python 3.13, numpy 2.5, pandas 3.0, scipy 1.18,
matplotlib 3.11. Simulations: Java 17.0.9 on Windows 11, reproduced on
Java 21 on Linux.
