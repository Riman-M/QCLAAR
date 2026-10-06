package org.iquantum.examples.hybrid;

import org.iquantum.backends.quantum.IBMQNode;
import org.iquantum.backends.quantum.QNode;
import org.iquantum.brokers.*;
import org.iquantum.core.iQuantum;
import org.iquantum.datacenters.QCloudDatacenter;
import org.iquantum.datacenters.QDatacenterCharacteristics;
import org.iquantum.datacenters.QEdgeDatacenter;
import org.iquantum.gateways.CloudGateway;
import org.iquantum.gateways.EdgeGateway;
import org.iquantum.policies.qtasks.QTaskSchedulerSpaceShared;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;
import org.iquantum.utils.QTaskExporter;
import org.iquantum.utils.QTaskImporter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Paper 1 revision experiments (SIMPAT major revision, Oct 2026).
 *
 * Same structure as iQuantumHybridMultiDatasetRunner. Set the flags below
 * and Run this file from the repo root. No command-line arguments needed.
 *
 *   EXPERIMENT = "E1" ... "E6", "E4b", or "ALL"
 *
 * Every experiment runs both No-Stress and Stress. Output goes to
 * results/revision/<EXPERIMENT>/ (one summary CSV + one log per experiment).
 *
 *   E1  Reproduction of FCFS, LoadAware, QCLAR, QCLAAR + seeded Lottery (seeds 1-5)
 *   E2  2x2 ablation: QCLAR/QCLAAR weights x queue term on/off
 *   E3  Component ablations, burden-only, capability-only, MCT (exact = clairvoyant reference)
 *   E4  Weight grid: 66 points on alpha+beta+gamma = 1, step 0.1
 *   E4b 1-D slice: gamma = 0.15, alpha 0.30..0.55 step 0.01, beta = 0.85 - alpha
 *   E5  MCT with runtime-estimate error, sigma in {0.1, 0.25, 0.5, 1.0}, seeds 1-5
 *   E6  10 resampled instances (80%, stratified by topology size)
 *
 * Policy names: FCFS, LoadAware, QCLAR, QCLAAR (original classes), LotterySeeded,
 * MCT, and "P:alpha/beta/gamma/lambda" (parametric composite score).
 */
public class iQuantumRevisionRunner {

    // -------------------------------------------------
    // Experiment configuration
    // -------------------------------------------------

    private static final String DATASET_DIR = "dataset/iquantum/";

    private static final List<String> DATASETS = Arrays.asList(
            "MQT-Set01-298-10-27-IBMQ27-Opt3-Extra.csv",
            "MQT-Set02-10-27-Mapped-AllAlgorithmLeft-Extra.csv",
            "MQT-Set03-7-127-AllOpt-IBMMapped-Only127-Extra.csv",
            "MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv"
    );

    // =================================================
    // FLAGS: edit, then Run this file
    // =================================================

    /** Which experiment: "E1", "E2", "E3", "E4", "E4b", "E5", "E6", or "ALL". */
    private static final String EXPERIMENT = "E6";

    /** E6 instance range. First batch: 1..10. Second batch: 11..20. */
    private static final int E6_FIRST_INSTANCE = 11;
    private static final int E6_LAST_INSTANCE = 20;

    /** Fraction of each dataset kept in a resampled instance (E6). */
    private static final double INSTANCE_FRACTION = 0.8;

    // -------------------------------------------------
    // Per-run settings, set by the experiment plan (do not edit)
    // -------------------------------------------------
    private static List<String> ALGORITHMS;
    private static long LOTTERY_SEED = 1L;
    private static int INSTANCE = 0;          // 0 = full dataset
    private static double MCT_SIGMA = 0.0;    // runtime-estimate error for MCT
    private static long MCT_SEED = 1L;

    /**
     * Run once with false (No-Stress), then again with true (Stress).
     */
    private static boolean APPLY_STRESS_TRANSFORM = false;   // set per condition by the plan

    /**
     * Keep false for clean stressed vs non-stressed comparison.
     */
    private static final boolean DUPLICATE_WORKLOAD = false;

    /**
     * Upgraded graded cloud heterogeneity.
     */
    private static final int[] CLOUD_NODE_IDS = {21, 22, 23, 24, 25, 26};
    private static final int[] CLOUD_CLOPS    = {220, 320, 450, 700, 1000, 1400};
    private static final int[] CLOUD_QV       = {32, 48, 64, 96, 128, 192};

    private static final int[] EDGE_NODE_IDS = {11, 12, 13, 14, 15};
    private static final String[] EDGE_BACKENDS = {
            "ibm_hanoi",
            "ibm_auckland",
            "ibm_cairo",
            "ibmq_mumbai",
            "ibmq_kolkata"
    };

    private static final DecimalFormat DF = new DecimalFormat("#0.0000########");

    // -------------------------------------------------
    // Main
    // -------------------------------------------------

    public static void main(String[] args) throws Exception {
        Log.disable();
        PrintStream console = System.out;
        List<String> exps = EXPERIMENT.equals("ALL")
                ? Arrays.asList("E1", "E2", "E3", "E4", "E4b", "E5", "E6")
                : Collections.singletonList(EXPERIMENT);
        console.println("Java " + System.getProperty("java.version") + " on " + System.getProperty("os.name"));

        for (String exp : exps) {
            File outDir = new File("results/revision/" + exp);
            outDir.mkdirs();
            String runTimestamp = nowStamp();
            String summaryCsvName = new File(outDir, "summary-" + exp + "-" + runTimestamp + ".csv").getPath();
            String logFileName = new File(outDir, "log-" + exp + "-" + runTimestamp + ".txt").getPath();
            PrintStream log = new PrintStream(new FileOutputStream(logFileName));
            System.setOut(log);
            initializeSummaryCsv(summaryCsvName);
            console.println("== " + exp + "  " + new Date());
            long t0 = System.currentTimeMillis();

            for (boolean stress : new boolean[]{false, true}) {
                APPLY_STRESS_TRANSFORM = stress;
                String scenarioTag = stress ? "stress" : "no_stress";
                for (RunSpec r : plan(exp)) {
                    ALGORITHMS = r.algorithms;
                    LOTTERY_SEED = r.lotterySeed; INSTANCE = r.instance;
                    MCT_SIGMA = r.mctSigma;       MCT_SEED = r.mctSeed;
                    console.println("   " + scenarioTag + "  instance=" + INSTANCE + " lotterySeed=" + LOTTERY_SEED
                            + " mctSigma=" + MCT_SIGMA + " mctSeed=" + MCT_SEED + "  "
                            + (ALGORITHMS.size() > 6 ? ALGORITHMS.size() + " policies" : ALGORITHMS));
                    for (String datasetFile : DATASETS) {
                        for (String algorithm : ALGORITHMS) {
                            try {
                                ExperimentSummary summary =
                                        runSingleExperiment(datasetFile, algorithm, runTimestamp, scenarioTag);
                                appendSummaryRow(summaryCsvName, summary);
                            } catch (Exception ex) {
                                System.out.println("FAILED: dataset=" + datasetFile + ", algorithm=" + algorithm);
                                ex.printStackTrace(System.out);
                                console.println("   FAILED: " + datasetFile + " " + algorithm + " (see log)");
                            }
                        }
                    }
                }
            }
            System.setOut(console);
            log.close();
            console.println("   done in " + (System.currentTimeMillis() - t0) / 1000 + " s -> " + summaryCsvName);
        }
        console.println("== finished " + new Date());
    }

    // -------------------------------------------------
    // Experiment plan
    // -------------------------------------------------

    private static class RunSpec {
        List<String> algorithms; long lotterySeed = 1L; int instance = 0; double mctSigma = 0.0; long mctSeed = 1L;
        RunSpec(String... algs) { algorithms = Arrays.asList(algs); }
        RunSpec lottery(long k) { lotterySeed = k; return this; }
        RunSpec instance(int i) { instance = i; return this; }
        RunSpec mct(double sigma, long seed) { mctSigma = sigma; mctSeed = seed; return this; }
    }

    private static List<RunSpec> plan(String exp) {
        List<RunSpec> p = new ArrayList<RunSpec>();
        switch (exp) {
            case "E1":
                p.add(new RunSpec("FCFS", "LoadAware", "QCLAR", "QCLAAR"));
                for (int k = 1; k <= 5; k++) p.add(new RunSpec("LotterySeeded").lottery(k));
                break;
            case "E2":
                p.add(new RunSpec("P:0.45/0.40/0.15/0.0", "P:0.40/0.30/0.15/0.15",
                        "P:0.45/0.40/0.15/0.15", "P:0.40/0.30/0.15/0.0"));
                break;
            case "E3":
                p.add(new RunSpec("P:1/0/0/0", "P:0.45/0.40/0/0", "P:0.45/0/0.15/0",
                        "P:0/0.40/0.15/0", "P:0/1/0/0", "P:0/0/1/0", "MCT"));
                break;
            case "E4": {
                List<String> g = new ArrayList<String>();
                for (int i = 0; i <= 10; i++) for (int j = 0; j <= 10 - i; j++)
                    g.add(String.format(Locale.ROOT, "P:%.1f/%.1f/%.1f/0", i / 10.0, j / 10.0, (10 - i - j) / 10.0));
                p.add(new RunSpec(g.toArray(new String[0])));
                break;
            }
            case "E4b": {
                List<String> g = new ArrayList<String>();
                for (int a = 30; a <= 55; a++)
                    g.add(String.format(Locale.ROOT, "P:%.2f/%.2f/0.15/0.0", a / 100.0, (85 - a) / 100.0));
                p.add(new RunSpec(g.toArray(new String[0])));
                break;
            }
            case "E5":   // error levels fixed in advance; do not add levels after seeing results
                for (double sg : new double[]{0.1, 0.25, 0.5, 1.0})
                    for (int k = 1; k <= 5; k++) p.add(new RunSpec("MCT").mct(sg, k));
                break;
            case "E6":
                for (int i = E6_FIRST_INSTANCE; i <= E6_LAST_INSTANCE; i++) {
                    p.add(new RunSpec("FCFS", "LoadAware", "QCLAR", "QCLAAR", "MCT").instance(i));
                    for (int k = 1; k <= 5; k++) p.add(new RunSpec("LotterySeeded").instance(i).lottery(k));
                    for (double sg : new double[]{0.25, 0.5}) p.add(new RunSpec("MCT").instance(i).mct(sg, i));
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown EXPERIMENT " + exp);
        }
        return p;
    }

    // -------------------------------------------------
    // Single experiment run
    // -------------------------------------------------

    private static ExperimentSummary runSingleExperiment(
            String datasetFile,
            String algorithm,
            String runTimestamp,
            String scenarioTag
    ) throws Exception {

        System.out.println("\n==================================================");
        System.out.println("Running dataset: " + datasetFile);
        System.out.println("Running algorithm: " + algorithm);
        System.out.println("Scenario: " + scenarioTag);
        System.out.println("==================================================");

        QEdgeBrokerParam.resetCounters(); QCloudBrokerParam.resetCounters();
        org.iquantum.policies.qtasks.QNodeSelectionLotterySeeded.setSeed(LOTTERY_SEED);
        QEdgeBrokerParam.MCT_SIGMA = MCT_SIGMA; QCloudBrokerParam.MCT_SIGMA = MCT_SIGMA;
        QEdgeBrokerParam.MCT_SEED = MCT_SEED;   QCloudBrokerParam.MCT_SEED = MCT_SEED;
        int numUser = 1;
        Calendar calendar = Calendar.getInstance();
        boolean traceFlag = true;
        iQuantum.init(numUser, calendar, traceFlag);

        QCloudDatacenter qcDatacenter = createQCDatacenter("QCloudDatacenter_" + algorithm);
        QEdgeDatacenter qeDatacenter = createQEDatacenter("QEdgeDatacenter_" + algorithm);

        CCloudBroker ccBroker = createClassicalCloudBroker("CCBroker_" + algorithm);
        CEdgeBroker ceBroker = createClassicalEdgeBroker("CEBroker_" + algorithm);

        QCloudBroker qcBroker = createQuantumCloudBroker(algorithm);
        QEdgeBroker qeBroker = createQuantumEdgeBroker(algorithm);

        CloudGateway cloudGateway = new CloudGateway(
                "CloudGateway_" + algorithm, ccBroker, qcBroker
        );
        EdgeGateway edgeGateway = new EdgeGateway(
                "EdgeGateway_" + algorithm, ceBroker, qeBroker, cloudGateway
        );

        List<QTask> submittedQTasks = createQTaskList(datasetFile, qeBroker);

        long simStartWallClock = System.currentTimeMillis();

        edgeGateway.submitQTasks(submittedQTasks);
        iQuantum.startSimulation();
        iQuantum.stopSimulation();

        long simEndWallClock = System.currentTimeMillis();

        String datasetTag = sanitizeDatasetName(datasetFile);
        String prefix = "iQuantumHybrid_" + datasetTag + "_" + algorithm + "_" + scenarioTag;

        List<QTask> qcTaskResults = qcBroker.getQTaskReceivedList();
        List<QTask> qeTaskResults = qeBroker.getQTaskReceivedList();

        Log.printLine("SIMULATION RESULTS - " + datasetFile + " - " + algorithm + " - " + scenarioTag);
        Log.printLine("==========================================================");

        Log.printLine();
        Log.printLine("CLOUD Layer ================================");
        if (qcTaskResults.isEmpty()) {
            Log.printLine("No QTask received");
        } else {
            
            
        }

        Log.printLine();
        Log.printLine("EDGE Layer ================================");
        if (qeTaskResults.isEmpty()) {
            Log.printLine("No QTask received");
        } else {
            
            
        }

        List<QTask> allCompletedTasks = new ArrayList<QTask>();
        allCompletedTasks.addAll(qcTaskResults);
        allCompletedTasks.addAll(qeTaskResults);

        ExperimentSummary summary = computeExperimentSummary(
                scenarioTag,
                datasetFile,
                algorithm,
                submittedQTasks,
                qeTaskResults,
                qcTaskResults,
                allCompletedTasks,
                simEndWallClock - simStartWallClock
        );

        System.out.println("COUNTERS\t"+scenarioTag+"\t"+datasetFile+"\t"+algorithm+"\tedge:"+QEdgeBrokerParam.decisions+","+QEdgeBrokerParam.decisionsAnyWaiting+","+QEdgeBrokerParam.selectedAgingNonzero+","+QEdgeBrokerParam.maxWaiting+","+QEdgeBrokerParam.sweeps+","+QEdgeBrokerParam.sweepStartLoadNonzero+"\tcloud:"+QCloudBrokerParam.decisions+","+QCloudBrokerParam.decisionsAnyWaiting+","+QCloudBrokerParam.selectedAgingNonzero+","+QCloudBrokerParam.maxWaiting+","+QCloudBrokerParam.sweeps+","+QCloudBrokerParam.sweepStartLoadNonzero);
        printSummaryToLog(summary);

        System.out.println("Completed: dataset=" + datasetFile + ", algorithm=" + algorithm);
        System.out.println("Cloud tasks completed: " + qcTaskResults.size());
        System.out.println("Edge tasks completed: " + qeTaskResults.size());
        System.out.println("Runner wall-clock time: " + (simEndWallClock - simStartWallClock) + " ms");

        return summary;
    }

    // -------------------------------------------------
    // Summary metrics
    // -------------------------------------------------

    private static ExperimentSummary computeExperimentSummary(
            String scenario,
            String dataset,
            String algorithm,
            List<QTask> submittedTasks,
            List<QTask> edgeCompletedTasks,
            List<QTask> cloudCompletedTasks,
            List<QTask> allCompletedTasks,
            long runnerWallClockMs
    ) {
        ExperimentSummary s = new ExperimentSummary();

        s.scenario = scenario;
        s.dataset = dataset;
        s.algorithm = algorithm;

        s.totalSubmitted = submittedTasks.size();
        s.edgeCompleted = edgeCompletedTasks.size();
        s.cloudCompleted = cloudCompletedTasks.size();
        s.totalCompleted = allCompletedTasks.size();
        s.totalIncomplete = s.totalSubmitted - s.totalCompleted;
        s.runnerWallClockMs = runnerWallClockMs;

        s.edgeExecutionRatio = safeDivide(s.edgeCompleted, s.totalCompleted);
        s.cloudExecutionRatio = safeDivide(s.cloudCompleted, s.totalCompleted);
        s.completionRatio = safeDivide(s.totalCompleted, s.totalSubmitted);

        if (allCompletedTasks.isEmpty()) {
            s.avgWaitingTime = 0.0;
            s.avgExecutionTime = 0.0;
            s.avgTurnaroundTime = 0.0;
            s.avgWallClockTime = 0.0;
            s.avgCost = 0.0;
            s.makespan = 0.0;
            s.throughput = 0.0;
            return s;
        }

        double sumWaiting = 0.0;
        double sumExecution = 0.0;
        double sumTurnaround = 0.0;
        double sumWallClock = 0.0;
        double sumCost = 0.0;

        double minSubmission = Double.MAX_VALUE;
        double maxFinish = -Double.MAX_VALUE;

        for (QTask q : allCompletedTasks) {
            double submission = q.getSubmissionTime();
            double waiting = q.getWaitingTime();
            double execution = q.getActualQPUTime();
            double finish = q.getFinishTime();
            double turnaround = finish - submission;
            double wallClock = q.getWallClockTime();
            double cost = q.getCost();

            sumWaiting += waiting;
            sumExecution += execution;
            sumTurnaround += turnaround;
            sumWallClock += wallClock;
            sumCost += cost;

            minSubmission = Math.min(minSubmission, submission);
            maxFinish = Math.max(maxFinish, finish);
        }

        s.avgWaitingTime = sumWaiting / allCompletedTasks.size();
        s.avgExecutionTime = sumExecution / allCompletedTasks.size();
        s.avgTurnaroundTime = sumTurnaround / allCompletedTasks.size();
        s.avgWallClockTime = sumWallClock / allCompletedTasks.size();
        s.avgCost = sumCost / allCompletedTasks.size();

        s.makespan = Math.max(0.0, maxFinish - minSubmission);
        s.throughput = s.makespan > 0.0 ? (s.totalCompleted / s.makespan) : 0.0;

        return s;
    }

    private static void printSummaryToLog(ExperimentSummary s) {
        System.out.println("-------------- SUMMARY --------------");
        System.out.println("Scenario              : " + s.scenario);
        System.out.println("Dataset               : " + s.dataset);
        System.out.println("Algorithm             : " + s.algorithm);
        System.out.println("Total Submitted       : " + s.totalSubmitted);
        System.out.println("Total Completed       : " + s.totalCompleted);
        System.out.println("Edge Completed        : " + s.edgeCompleted);
        System.out.println("Cloud Completed       : " + s.cloudCompleted);
        System.out.println("Incomplete            : " + s.totalIncomplete);
        System.out.println("Completion Ratio      : " + DF.format(s.completionRatio));
        System.out.println("Edge Execution Ratio  : " + DF.format(s.edgeExecutionRatio));
        System.out.println("Cloud Execution Ratio : " + DF.format(s.cloudExecutionRatio));
        System.out.println("Avg Waiting Time      : " + DF.format(s.avgWaitingTime));
        System.out.println("Avg Execution Time    : " + DF.format(s.avgExecutionTime));
        System.out.println("Avg Turnaround Time   : " + DF.format(s.avgTurnaroundTime));
        System.out.println("Avg Wall Clock Time   : " + DF.format(s.avgWallClockTime));
        System.out.println("Makespan              : " + DF.format(s.makespan));
        System.out.println("Throughput            : " + DF.format(s.throughput));
        System.out.println("Avg Cost              : " + DF.format(s.avgCost));
        System.out.println("Runner WallClock (ms) : " + s.runnerWallClockMs);
        System.out.println("-------------------------------------");
    }

    // -------------------------------------------------
    // Summary CSV helpers
    // -------------------------------------------------

    private static void initializeSummaryCsv(String fileName) throws IOException {
        File file = new File(fileName);
        boolean writeHeader = !file.exists() || file.length() == 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            if (writeHeader) {
                writer.write(
                        "scenario,dataset,algorithm,total_submitted,total_completed," +
                                "edge_completed,cloud_completed,total_incomplete," +
                                "completion_ratio,edge_execution_ratio,cloud_execution_ratio," +
                                "avg_waiting_time,avg_execution_time,avg_turnaround_time," +
                                "avg_wall_clock_time,makespan,throughput,avg_cost,runner_wall_clock_ms,instance,lottery_seed,mct_sigma,mct_seed"
                );
                writer.newLine();
            }
        }
    }

    private static void appendSummaryRow(String fileName, ExperimentSummary s) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            writer.write(csv(s.scenario)); writer.write(",");
            writer.write(csv(s.dataset)); writer.write(",");
            writer.write(csv(s.algorithm)); writer.write(",");
            writer.write(String.valueOf(s.totalSubmitted)); writer.write(",");
            writer.write(String.valueOf(s.totalCompleted)); writer.write(",");
            writer.write(String.valueOf(s.edgeCompleted)); writer.write(",");
            writer.write(String.valueOf(s.cloudCompleted)); writer.write(",");
            writer.write(String.valueOf(s.totalIncomplete)); writer.write(",");
            writer.write(DF.format(s.completionRatio)); writer.write(",");
            writer.write(DF.format(s.edgeExecutionRatio)); writer.write(",");
            writer.write(DF.format(s.cloudExecutionRatio)); writer.write(",");
            writer.write(DF.format(s.avgWaitingTime)); writer.write(",");
            writer.write(DF.format(s.avgExecutionTime)); writer.write(",");
            writer.write(DF.format(s.avgTurnaroundTime)); writer.write(",");
            writer.write(DF.format(s.avgWallClockTime)); writer.write(",");
            writer.write(DF.format(s.makespan)); writer.write(",");
            writer.write(DF.format(s.throughput)); writer.write(",");
            writer.write(DF.format(s.avgCost)); writer.write(",");
            writer.write(String.valueOf(s.runnerWallClockMs)); writer.write(",");
            writer.write(INSTANCE + "," + LOTTERY_SEED + "," + MCT_SIGMA + "," + MCT_SEED);
            writer.newLine();
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    // -------------------------------------------------
    // Brokers
    // -------------------------------------------------

    private static CCloudBroker createClassicalCloudBroker(String name) {
        try {
            return new CCloudBroker(name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create CCloudBroker: " + name, e);
        }
    }

    private static CEdgeBroker createClassicalEdgeBroker(String name) {
        try {
            return new CEdgeBroker(name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create CEdgeBroker: " + name, e);
        }
    }

    private static QCloudBroker createQuantumCloudBroker(String algorithm) {
        try {
            switch (algorithm) {
                case "Lottery":
                    return new QCloudBroker("QCloudBrokerLottery");
                case "FCFS":
                    return new QCloudBrokerFCFS("QCloudBrokerFCFS");
                case "LoadAware":
                    return new QCloudBrokerLoadAware("QCloudBrokerLoadAware");
                case "QCLAR":
                    return new QCloudBrokerQCLAR("QCloudBrokerQCLAR");
                case "QCLAAR":
                    return new QCloudBrokerQCLAAR("QCloudBrokerQCLAAR");
                default:
                    if (algorithm.equals("LotterySeeded")) return new QCloudBrokerLotterySeeded("QCloudBrokerLotterySeeded");
                    if (algorithm.equals("MCT")) { QCloudBrokerParam b=new QCloudBrokerParam("QCloudBrokerMCT",0,0,0,0); b.setMCT(true); return b; }
                    if (algorithm.startsWith("P:")) { double[] w=parseW(algorithm); return new QCloudBrokerParam("QCloudBrokerParam", w[0],w[1],w[2],w[3]); }
                    throw new IllegalArgumentException("Unsupported cloud algorithm: " + algorithm);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create cloud broker for " + algorithm, e);
        }
    }

    private static QEdgeBroker createQuantumEdgeBroker(String algorithm) {
        try {
            switch (algorithm) {
                case "Lottery":
                    return new QEdgeBroker("QEdgeBrokerLottery");
                case "FCFS":
                    return new QEdgeBrokerFCFS("QEdgeBrokerFCFS");
                case "LoadAware":
                    return new QEdgeBrokerLoadAware("QEdgeBrokerLoadAware");
                case "QCLAR":
                    return new QEdgeBrokerQCLAR("QEdgeBrokerQCLAR");
                case "QCLAAR":
                    return new QEdgeBrokerQCLAAR("QEdgeBrokerQCLAAR");
                default:
                    if (algorithm.equals("LotterySeeded")) return new QEdgeBrokerLotterySeeded("QEdgeBrokerLotterySeeded");
                    if (algorithm.equals("MCT")) { QEdgeBrokerParam b=new QEdgeBrokerParam("QEdgeBrokerMCT",0,0,0,0); b.setMCT(true); return b; }
                    if (algorithm.startsWith("P:")) { double[] w=parseW(algorithm); return new QEdgeBrokerParam("QEdgeBrokerParam", w[0],w[1],w[2],w[3]); }
                    throw new IllegalArgumentException("Unsupported edge algorithm: " + algorithm);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create edge broker for " + algorithm, e);
        }
    }

    // -------------------------------------------------
    // Workload generation
    // -------------------------------------------------

    private static List<QTask> createQTaskList(String datasetFile, QBroker qBroker) {
        List<QTask> qTaskList = new ArrayList<QTask>();

        Path datasetPath = Paths.get(System.getProperty("user.dir"), DATASET_DIR, datasetFile);
        QTaskImporter importer = new QTaskImporter();

        try {
            List<QTask> importedQTasks = importer.importQTasksFromCsv(datasetPath.toString());

            for (int i = 0; i < importedQTasks.size(); i++) {
                QTask original = importedQTasks.get(i);
                QTask finalTask;

                if (APPLY_STRESS_TRANSFORM) {
                    finalTask = createStressTransformedTask(original, i, qBroker.getId());
                } else {
                    original.setBrokerId(qBroker.getId());
                    original.setQNodeId(-1);
                    finalTask = original;
                }

                qTaskList.add(finalTask);
            }

            if (APPLY_STRESS_TRANSFORM && DUPLICATE_WORKLOAD) {
                List<QTask> duplicated = new ArrayList<QTask>();
                int offset = 1000000;

                for (QTask original : qTaskList) {
                    QTask copy = cloneQTaskWithNewId(
                            original,
                            original.getQTaskId() + offset,
                            qBroker.getId()
                    );
                    duplicated.add(copy);
                }

                qTaskList.addAll(duplicated);
            }

        } catch (IOException e) {
            throw new RuntimeException("Error reading dataset: " + datasetPath, e);
        }

        if (INSTANCE > 0) qTaskList = subsample(qTaskList, INSTANCE, INSTANCE_FRACTION);
        return qTaskList;
    }

    /** Stratified resample: within each qubit-topology size, keep round(frac*n) tasks chosen
     *  with Random(instance); original task order is preserved, so each task keeps the
     *  stress class it was given in the full dataset. */
    private static List<QTask> subsample(List<QTask> tasks, int instance, double frac) {
        Map<Integer, List<Integer>> strata = new TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < tasks.size(); i++) {
            int key = tasks.get(i).getQubitTopology().getNumQubits();
            strata.computeIfAbsent(key, k -> new ArrayList<Integer>()).add(i);
        }
        Random rng = new Random(instance);
        Set<Integer> keep = new HashSet<Integer>();
        for (List<Integer> idx : strata.values()) {
            List<Integer> c = new ArrayList<Integer>(idx);
            Collections.shuffle(c, rng);
            keep.addAll(c.subList(0, (int) Math.round(frac * c.size())));
        }
        List<QTask> out = new ArrayList<QTask>();
        for (int i = 0; i < tasks.size(); i++) if (keep.contains(i)) out.add(tasks.get(i));
        return out;
    }

    /**
     * Stress transform:
     * 50% small
     * 30% medium
     * 20% heavy
     *
     * Adjusts layers and shots only, preserving topology and gate structure.
     */
    private static QTask createStressTransformedTask(QTask original, int index, int brokerId) {
        int bucket = index % 10;

        int newLayers = original.getNumLayers();
        int newShots = original.getNumShots();

        if (bucket < 5) {
            // 50% small
            newLayers = Math.max(10, original.getNumLayers() / 2);
            newShots = Math.max(500, original.getNumShots() / 2);
        } else if (bucket < 8) {
            // 30% medium -> unchanged
        } else {
            // 20% heavy
            newLayers = original.getNumLayers() * 3;
            newShots = original.getNumShots() * 3;
        }

        QTask transformed = new QTask(
                original.getQTaskId(),
                original.getNumQubits(),
                newLayers,
                newShots,
                new ArrayList<String>(original.getGateSet()),
                original.getQubitTopology()
        );

        transformed.setBrokerId(brokerId);
        transformed.setQNodeId(-1);
        return transformed;
    }

    private static QTask cloneQTaskWithNewId(QTask original, int newId, int brokerId) {
        QTask copy = new QTask(
                newId,
                original.getNumQubits(),
                original.getNumLayers(),
                original.getNumShots(),
                new ArrayList<String>(original.getGateSet()),
                original.getQubitTopology()
        );
        copy.setBrokerId(brokerId);
        copy.setQNodeId(-1);
        return copy;
    }

    // -------------------------------------------------
    // Datacenters
    // -------------------------------------------------

    private static QEdgeDatacenter createQEDatacenter(String name) {
        List<QNode> qeNodeList = new ArrayList<QNode>();

        for (int i = 0; i < EDGE_NODE_IDS.length; i++) {
            QNode qNode = IBMQNode.createNode(
                    EDGE_NODE_IDS[i],
                    EDGE_BACKENDS[i],
                    new QTaskSchedulerSpaceShared()
            );
            qeNodeList.add(qNode);
        }

        double timeZone = 0.0;
        double costPerSec = 1.6;

        QDatacenterCharacteristics characteristics =
                new QDatacenterCharacteristics(qeNodeList, timeZone, costPerSec);

        return new QEdgeDatacenter(name, characteristics);
    }

    private static QCloudDatacenter createQCDatacenter(String name) {
        List<QNode> qcNodeList = new ArrayList<QNode>();

        for (int i = 0; i < CLOUD_NODE_IDS.length; i++) {
            QNode qcNode = IBMQNode.createNode(
                    CLOUD_NODE_IDS[i],
                    "ibm_washington",
                    new QTaskSchedulerSpaceShared()
            );

            qcNode.setCLOPS(CLOUD_CLOPS[i]);
            qcNode.setQuantumVolume(CLOUD_QV[i]);

            qcNodeList.add(qcNode);
        }

        double timeZone = 0.0;
        double costPerSec = 1.6;

        QDatacenterCharacteristics characteristics =
                new QDatacenterCharacteristics(qcNodeList, timeZone, costPerSec);

        return new QCloudDatacenter(name, characteristics);
    }

    // -------------------------------------------------
    // Helpers
    // -------------------------------------------------

    private static String sanitizeDatasetName(String datasetFile) {
        String name = datasetFile;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static double[] parseW(String a){ String[] p=a.substring(2).split("/"); double[] w=new double[4]; for(int i=0;i<4;i++) w[i]=Double.parseDouble(p[i]); return w; }

    private static String nowStamp() {
        return new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss").format(new Date());
    }

    private static double safeDivide(double numerator, double denominator) {
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    // -------------------------------------------------
    // Summary data holder
    // -------------------------------------------------

    private static class ExperimentSummary {
        String scenario;
        String dataset;
        String algorithm;

        int totalSubmitted;
        int totalCompleted;
        int edgeCompleted;
        int cloudCompleted;
        int totalIncomplete;

        double completionRatio;
        double edgeExecutionRatio;
        double cloudExecutionRatio;

        double avgWaitingTime;
        double avgExecutionTime;
        double avgTurnaroundTime;
        double avgWallClockTime;
        double makespan;
        double throughput;
        double avgCost;

        long runnerWallClockMs;
    }
}