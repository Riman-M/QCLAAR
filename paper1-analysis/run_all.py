"""Run the full Paper 1 revision analysis: python run_all.py

Order: verification, tables, instance tests, figures, quoted numbers, workload statistics, fold tests, subsample overlap.
Outputs go to output/ (tables/, figures/, verification.txt, quoted_numbers.txt).
"""
import runpy, sys
from pathlib import Path

here = Path(__file__).resolve().parent / "analysis"
sys.path.insert(0, str(here))
for step in ["step1_verify", "step2_tables", "step3_instance_tests", "step4_figures", "step5_quoted_numbers", "step6_workload", "step7_fold_tests", "step8_overlap"]:
    print(f"\n===== {step} =====")
    runpy.run_path(str(here / f"{step}.py"), run_name="__main__")
print("\nDone. See the output folder.")
