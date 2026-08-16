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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Multi-dataset automatic experiment runner for iQuantum.
 *
 * Runs all configured datasets against all configured algorithms
 * and exports:
 *   <prefix>-cloud-<timestamp>.csv
 *   <prefix>-edge-<timestamp>.csv
 *
 * Recommended first run:
 *   - keep APPLY_STRESS_TRANSFORM = false
 *   - verify all dataset x algorithm combinations complete correctly
 *
 * Then second run:
 *   - set APPLY_STRESS_TRANSFORM = true
 *   - compare whether QCLAR / QCLAAR diverge more clearly
 */
public class iQuantumHybridMultiDatasetRunnerBKP {

    // -----------------------------
    // Experiment configuration
    // -----------------------------

    private static final String DATASET_DIR = "dataset/iquantum/";

    private static final List<String> DATASETS = Arrays.asList(
            "MQT-Set01-298-10-27-IBMQ27-Opt3-Extra.csv",
            "MQT-Set02-10-27-Mapped-AllAlgorithmLeft-Extra.csv",
            "MQT-Set03-7-127-AllOpt-IBMMapped-Only127-Extra.csv",
            "MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv"
    );

    private static final List<String> ALGORITHMS = Arrays.asList(
            "Lottery",
            "FCFS",
            "LoadAware",
            "QCLAR",
            "QCLAAR"
    );

    /**
     * Keep false for pure real-dataset evaluation.
     * Turn true for stress experiments.
     */
    private static final boolean APPLY_STRESS_TRANSFORM = true;

    /**
     * If true and stress transform is enabled, doubles workload size.
     */
    private static final boolean DUPLICATE_WORKLOAD = true;

    /**
     * Upgraded graded cloud heterogeneity.
     */
    private static final int[] CLOUD_NODE_IDS = {21, 22, 23, 24, 25, 26};
    private static final int[] CLOUD_CLOPS   = {220, 320, 450, 700, 1000, 1400};
    private static final int[] CLOUD_QV      = {32, 48, 64, 96, 128, 192};

    private static final int[] EDGE_NODE_IDS = {11, 12, 13, 14, 15};
    private static final String[] EDGE_BACKENDS = {
            "ibm_hanoi",
            "ibm_auckland",
            "ibm_cairo",
            "ibmq_mumbai",
            "ibmq_kolkata"
    };

    // -----------------------------
    // Main
    // -----------------------------

    public static void main(String[] args) throws Exception {
//        Log.disable();

        String runTimestamp = nowStamp();

        try {
            PrintStream fileOut = new PrintStream(
                    new FileOutputStream("iQuantumMultiDatasetRunner-" + runTimestamp + ".txt")
            );
            System.setOut(fileOut);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Start iQuantumHybridMultiDatasetRunner");
        System.out.println("Run timestamp: " + runTimestamp);
        System.out.println("Datasets: " + DATASETS);
        System.out.println("Algorithms: " + ALGORITHMS);
        System.out.println("Stress transform enabled: " + APPLY_STRESS_TRANSFORM);
        System.out.println("Duplicate workload enabled: " + DUPLICATE_WORKLOAD);

        long globalStart = System.currentTimeMillis();

        for (String datasetFile : DATASETS) {
            for (String algorithm : ALGORITHMS) {
                try {
                    runSingleExperiment(datasetFile, algorithm, runTimestamp);
                } catch (Exception ex) {
                    System.out.println("FAILED: dataset=" + datasetFile + ", algorithm=" + algorithm);
                    ex.printStackTrace();
                }
            }
        }

        long globalEnd = System.currentTimeMillis();
        System.out.println("All experiments finished in " + (globalEnd - globalStart) + " ms");
    }

    // -----------------------------
    // Single experiment run
    // -----------------------------

    private static void runSingleExperiment(String datasetFile, String algorithm, String runTimestamp) throws Exception {
        System.out.println("\n==================================================");
        System.out.println("Running dataset: " + datasetFile);
        System.out.println("Running algorithm: " + algorithm);
        System.out.println("==================================================");

        int num_user = 1;
        Calendar calendar = Calendar.getInstance();
        boolean trace_flag = true;
        iQuantum.init(num_user, calendar, trace_flag);

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

        List<QTask> qTaskList = createQTaskList(datasetFile, qeBroker);

        long simStart = System.currentTimeMillis();

        edgeGateway.submitQTasks(qTaskList);
        iQuantum.startSimulation();
        iQuantum.stopSimulation();

        long simEnd = System.currentTimeMillis();

        String datasetTag = sanitizeDatasetName(datasetFile);
        String prefix = "iQuantumHybrid_" + datasetTag + "_" + algorithm;

        List<QTask> qcTaskResults = qcBroker.getQTaskReceivedList();
        List<QTask> qeTaskResults = qeBroker.getQTaskReceivedList();

        Log.printLine("SIMULATION RESULTS - " + datasetFile + " - " + algorithm);
        Log.printLine("==========================================================");

        Log.printLine();
        Log.printLine("CLOUD Layer ================================");
        if (qcTaskResults.isEmpty()) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qcTaskResults);
            QTaskExporter.extractQTaskListToCSV(qcTaskResults, prefix + "-cloud-" + runTimestamp);
        }

        Log.printLine();
        Log.printLine("EDGE Layer ================================");
        if (qeTaskResults.isEmpty()) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qeTaskResults);
            QTaskExporter.extractQTaskListToCSV(qeTaskResults, prefix + "-edge-" + runTimestamp);
        }

        System.out.println("Completed: dataset=" + datasetFile + ", algorithm=" + algorithm);
        System.out.println("Cloud tasks completed: " + qcTaskResults.size());
        System.out.println("Edge tasks completed: " + qeTaskResults.size());
        System.out.println("Simulation time: " + (simEnd - simStart) + " ms");
    }

    // -----------------------------
    // Brokers
    // -----------------------------

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
                    throw new IllegalArgumentException("Unsupported cloud algorithm: " + algorithm);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create cloud broker for " + algorithm, e);
        }
    }

    private static QEdgeBroker createQuantumEdgeBroker(String algorithm) {
        try {
            if ("FCFS".equalsIgnoreCase(algorithm)) {
                return new QEdgeBrokerFCFS("QEdgeBrokerFCFS");
            }
            return new QEdgeBroker("QEdgeBroker");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create edge broker for " + algorithm, e);
        }
    }

    // -----------------------------
    // Workload generation
    // -----------------------------

    private static List<QTask> createQTaskList(String datasetFile, QBroker qBroker) {
        List<QTask> qTaskList = new ArrayList<>();

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
                List<QTask> duplicated = new ArrayList<>();
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

        return qTaskList;
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
                new ArrayList<>(original.getGateSet()),
                original.getQubitTopology()
        );

        transformed.setBrokerId(brokerId);
        transformed.setQNodeId(-1);

        return transformed;
    }

    /**
     * Simple cloning utility using QTask constructor pattern consistent with existing examples.
     * Adjust this if your local QTask class exposes a different constructor signature.
     */
    private static QTask cloneQTaskWithNewId(QTask original, int newId, int brokerId) {
        QTask copy = new QTask(
                newId,
                original.getNumQubits(),
                original.getNumLayers(),
                original.getNumShots(),
                new ArrayList<>(original.getGateSet()),
                original.getQubitTopology()
        );
        copy.setBrokerId(brokerId);
        copy.setQNodeId(-1);
        return copy;
    }

    // -----------------------------
    // Datacenters
    // -----------------------------

    private static QEdgeDatacenter createQEDatacenter(String name) {
        List<QNode> qeNodeList = new ArrayList<>();

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
        List<QNode> qcNodeList = new ArrayList<>();

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

    // -----------------------------
    // Helpers
    // -----------------------------

    private static String sanitizeDatasetName(String datasetFile) {
        String name = datasetFile;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static String nowStamp() {
        return new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss").format(new Date());
    }
}