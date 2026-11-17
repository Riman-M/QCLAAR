"""Step 4: figures of the revised manuscript.

File names match those used in the manuscript source:
  Figure2_no_stress_waiting_time.pdf   (Fig. 4)   Figure5_stress_waiting_time.pdf (Fig. 5)
  Figure3_no_stress_makespan.pdf       (Fig. 6)   Figure6_stress_makespan.pdf     (Fig. 7)
  Figure8_lottery_variability_no_stress.pdf / _stress.pdf                         (Fig. 8)
  fig_weight_slice.pdf (Fig. 9)  fig_weight_grid_set03.pdf (Fig. 10)  fig_mct_error.pdf (Fig. 11)
Figures 1-3 (framework, pipeline, execution regime) are unchanged from the
original submission and are not regenerated here.
"""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from common import OUT_FIGS, SETS, POLICIES, load, full_dataset_tables, value, weights

plt.rcParams.update({"font.size": 9, "pdf.fonttype": 42})
det, lot = full_dataset_tables()
COL = {"FCFS": (0.298, 0.471, 0.659), "Lottery": (0.961, 0.522, 0.094), "LoadAware": (0.329, 0.635, 0.294),
       "QCLAR": (0.894, 0.341, 0.337), "QCLAAR": (0.447, 0.718, 0.698)}
SETCOL = {"Set01": "#4C78A8", "Set02": "#54A24B", "Set03": "#E45756", "Set04": "#B279A2"}

def bars(sc, metric, ylabel, fname, title):
    fig, axs = plt.subplots(2, 2, figsize=(7.2, 5.2))
    for ax, s in zip(axs.flat, SETS):
        v = [value(det, lot, sc, s, p, metric) for p in POLICIES]
        ci = lot.loc[(sc, s), "wait_ci" if metric == "avg_waiting_time" else "mk_ci"]
        err = [ci if p == "Lottery" else np.nan for p in POLICIES]
        ax.bar(range(5), v, color=[COL[p] for p in POLICIES], edgecolor="k", linewidth=0.6, yerr=err, capsize=3)
        ax.set_xticks(range(5)); ax.set_xticklabels(POLICIES, rotation=25, fontsize=7)
        ax.set_title(s, fontsize=9, fontweight="bold"); ax.set_ylabel(ylabel, fontsize=8)
        ax.grid(axis="y", ls=":", lw=0.5); ax.set_axisbelow(True)
        ax.ticklabel_format(axis="y", style="sci", scilimits=(0, 3))
    fig.suptitle(title, fontsize=10, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.96)); fig.savefig(OUT_FIGS / fname); plt.close(fig)

bars("no_stress", "avg_waiting_time", "Average waiting time", "Figure2_no_stress_waiting_time.pdf", "No-stress condition: average waiting time")
bars("stress", "avg_waiting_time", "Average waiting time", "Figure5_stress_waiting_time.pdf", "Stress condition: average waiting time")
bars("no_stress", "makespan", "Makespan", "Figure3_no_stress_makespan.pdf", "No-stress condition: makespan")
bars("stress", "makespan", "Makespan", "Figure6_stress_makespan.pdf", "Stress condition: makespan")

# Lottery variability (mean and 95% CI over seeds)
for sc, lab, fname in [("no_stress", "No Stress", "Figure8_lottery_variability_no_stress.pdf"),
                       ("stress", "Stress", "Figure8_lottery_variability_stress.pdf")]:
    fig, ax = plt.subplots(figsize=(4.2, 3.0))
    ax.bar(SETS, [lot.loc[(sc, s), "mk"] for s in SETS], yerr=[lot.loc[(sc, s), "mk_ci"] for s in SETS],
           capsize=4, color=COL["Lottery"], edgecolor="k", linewidth=0.6)
    ax.set_xlabel("Dataset"); ax.set_ylabel("Lottery makespan (mean, 95% CI)")
    ax.set_title(f"Lottery Variability ({lab})", fontsize=9, fontweight="bold")
    ax.grid(axis="y", ls=":", lw=0.5); ax.set_axisbelow(True); ax.ticklabel_format(axis="y", style="sci", scilimits=(0, 3))
    fig.tight_layout(); fig.savefig(OUT_FIGS / fname); plt.close(fig)

la = det.xs("LoadAware", level="algorithm")

# weight slice (E4b)
S = load("E4b"); S["alpha"] = S.algorithm.map(lambda a: weights(a)[0])
fig, axs = plt.subplots(1, 2, figsize=(7.2, 3.5))
for ax, (m, lab) in zip(axs, [("avg_waiting_time", "Average waiting time"), ("makespan", "Makespan")]):
    for s in SETS:
        for sc, ls in [("no_stress", "-"), ("stress", "--")]:
            d = S[(S.set == s) & (S.scenario == sc)].sort_values("alpha")
            ax.plot(d.alpha, 100 * (d[m] / la.loc[(sc, s), m] - 1), ls=ls, color=SETCOL[s], lw=1.2,
                    label=f"{s}, {'no stress' if sc == 'no_stress' else 'stress'}")
    ax.axhline(0, color="k", lw=0.6); ax.axvline(0.45, color="grey", lw=0.8, ls=":")
    ax.axvspan(0.495, 0.555, color="0.9", zorder=0)
    ax.set_xlabel(r"$\alpha$  ($\gamma=0.15$, $\beta=0.85-\alpha$)"); ax.set_ylabel(f"{lab}: % vs LoadAware")
    ax.set_xlim(0.30, 0.55); ax.grid(ls=":", lw=0.5)
h, l = axs[0].get_legend_handles_labels()
fig.legend(h, l, loc="lower center", ncol=4, fontsize=7, frameon=False)
fig.tight_layout(rect=(0, 0.13, 1, 1)); fig.savefig(OUT_FIGS / "fig_weight_slice.pdf"); plt.close(fig)

# weight grid on Set03 (E4)
G = load("E4")
w = G.algorithm.map(weights)
G["a"], G["b"], G["c"] = [w.map(lambda t: t[i]) for i in range(3)]
def xy(a, b, c): return b + 0.5 * c, c * np.sqrt(3) / 2
fig, axs = plt.subplots(2, 2, figsize=(6.3, 6.5))
H = np.sqrt(3) / 2
for i, sc in enumerate(["no_stress", "stress"]):
    for j, (m, lab) in enumerate([("avg_waiting_time", "average waiting time"), ("makespan", "makespan")]):
        ax = axs[i, j]; sub = G[(G.scenario == sc) & (G.set == "Set03")]
        rel = 100 * (sub[m] / la.loc[(sc, "Set03"), m] - 1); x, y = xy(sub.a, sub.b, sub.c)
        sc_ = ax.scatter(x, y, c=rel.clip(-70, 70), cmap="RdBu_r", vmin=-70, vmax=70, s=270, marker="h",
                         edgecolors="0.35", linewidths=0.4, zorder=2)
        for xx, yy, v in zip(x, y, rel):
            ax.text(xx, yy, f"{v:.0f}", ha="center", va="center", fontsize=6.6, zorder=3,
                    color="white" if abs(v) >= 45 else "black")
        ax.plot(*xy(0.45, 0.40, 0.15), "*", ms=9, mfc="gold", mec="k", mew=0.8, zorder=4)
        ax.plot([0, 1, 0.5, 0], [0, 0, H, 0], "-", color="0.5", lw=0.5, zorder=1)
        ax.text(-0.07, -0.08, r"$\alpha=1$" + "\n(load)", ha="center", va="top", fontsize=8)
        ax.text(1.07, -0.08, r"$\beta=1$" + "\n(speed)", ha="center", va="top", fontsize=8)
        ax.text(0.5, H + 0.07, r"$\gamma=1$ (capability)", ha="center", va="bottom", fontsize=8)
        ax.set_title(f"({'ab'[j] if i == 0 else 'cd'[j]}) {'No stress' if sc == 'no_stress' else 'Stress'}: {lab}",
                     fontsize=9, pad=16)
        ax.set_xlim(-0.16, 1.16); ax.set_ylim(-0.24, H + 0.14)
        ax.axis("off"); ax.set_aspect("equal")
fig.subplots_adjust(left=0.02, right=0.84, top=0.95, bottom=0.03, wspace=0.08, hspace=0.18)
cax = fig.add_axes([0.87, 0.30, 0.022, 0.40])
cb = fig.colorbar(sc_, cax=cax); cb.set_label("% change relative to LoadAware (negative = better)", fontsize=8)
cb.ax.tick_params(labelsize=8)
fig.savefig(OUT_FIGS / "fig_weight_grid_set03.pdf", bbox_inches="tight"); plt.close(fig)

# MCT with estimate error (E3 for sigma = 0, E5 for sigma > 0)
E5, E3 = load("E5"), load("E3")
q = det.xs("QCLAR", level="algorithm")
m0 = E3[E3.algorithm == "MCT"].set_index(["scenario", "set"])
sig = [0, 0.1, 0.25, 0.5, 1.0]
fig, axs = plt.subplots(1, 2, figsize=(7.2, 3.5))
for ax, (m, lab) in zip(axs, [("avg_waiting_time", "Average waiting time"), ("makespan", "Makespan")]):
    for s in SETS:
        for sc, ls, mk in [("no_stress", "-", "o"), ("stress", "--", "s")]:
            mean = [100 * (m0.loc[(sc, s), m] / q.loc[(sc, s), m] - 1)]; lo = [mean[0]]; hi = [mean[0]]
            for sg in sig[1:]:
                v = 100 * (E5[(E5.scenario == sc) & (E5.set == s) & np.isclose(E5.mct_sigma, sg)][m] / q.loc[(sc, s), m] - 1)
                mean.append(v.mean()); lo.append(v.min()); hi.append(v.max())
            ax.plot(sig, mean, ls=ls, marker=mk, ms=3, color=SETCOL[s], lw=1.1,
                    label=f"{s}, {'no stress' if sc == 'no_stress' else 'stress'}")
            ax.fill_between(sig, lo, hi, color=SETCOL[s], alpha=0.12, lw=0)
    ax.axhline(0, color="k", lw=0.6); ax.set_xlabel(r"Estimate error $\sigma$"); ax.set_ylabel(f"{lab}: MCT % vs QCLAR")
    ax.grid(ls=":", lw=0.5); ax.set_xticks([0, 0.25, 0.5, 0.75, 1.0])
h, l = axs[0].get_legend_handles_labels()
fig.legend(h, l, loc="lower center", ncol=4, fontsize=7, frameon=False)
fig.tight_layout(rect=(0, 0.13, 1, 1)); fig.savefig(OUT_FIGS / "fig_mct_error.pdf"); plt.close(fig)
print("figures written to", OUT_FIGS)
