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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class iQuantumHybridExample4AllAlgorithms {

    private static final String DATASET_PATH =
            "dataset/iquantum/MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv";

    public static void main(String[] args) throws Exception {
//        Log.disable();

        try {
            PrintStream fileOut = new PrintStream(
                    new FileOutputStream("iQuantumHybridExample4AllAlgorithms.txt")
            );
            System.setOut(fileOut);
        } catch (Exception e) {
            e.printStackTrace();
        }

        long globalStart = System.currentTimeMillis();
        System.out.println("Start iQuantumHybridExample4AllAlgorithms");

        runExperiment("Lottery");
        runExperiment("FCFS");
        runExperiment("LoadAware");
        runExperiment("QCLAR");
        runExperiment("QCLAAR");

        long globalEnd = System.currentTimeMillis();
        System.out.println("All experiments finished in "
                + (globalEnd - globalStart) + " ms");
    }

    private static void runExperiment(String algorithm) throws Exception {
        System.out.println("\n==================================================");
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

        List<QTask> qTaskList = createQTaskList(qeBroker);

        edgeGateway.submitQTasks(qTaskList);

        long start = System.currentTimeMillis();
        iQuantum.startSimulation();
        iQuantum.stopSimulation();
        long end = System.currentTimeMillis();

        String exampleName = "iQuantumHybridExample4" + algorithm;

        Log.printLine("SIMULATION RESULTS - " + algorithm);
        Log.printLine("==========================================================");

        List<QTask> qcTaskResults = qcBroker.getQTaskReceivedList();
        Log.printLine();
        Log.printLine("CLOUD Layer ================================");
        if (qcTaskResults.size() == 0) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qcTaskResults);
            QTaskExporter.extractQTaskListToCSV(qcTaskResults, exampleName + "-cloud");
        }

        List<QTask> qeTaskResults = qeBroker.getQTaskReceivedList();
        Log.printLine();
        Log.printLine("EDGE Layer ================================");
        if (qeTaskResults.size() == 0) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qeTaskResults);
            QTaskExporter.extractQTaskListToCSV(qeTaskResults, exampleName + "-edge");
        }

        System.out.println("Algorithm " + algorithm + " completed in " + (end - start) + " ms");
        System.out.println("Cloud tasks completed: " + qcTaskResults.size());
        System.out.println("Edge tasks completed: " + qeTaskResults.size());
    }

    private static CCloudBroker createClassicalCloudBroker(String name) {
        try {
            return new CCloudBroker(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static CEdgeBroker createClassicalEdgeBroker(String name) {
        try {
            return new CEdgeBroker(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static QCloudBroker createQuantumCloudBroker(String algorithm) {
        try {
            if ("Lottery".equalsIgnoreCase(algorithm)) {
                return new QCloudBroker("QCloudBrokerLottery");
            } else if ("FCFS".equalsIgnoreCase(algorithm)) {
                return new QCloudBrokerFCFS("QCloudBrokerFCFS");
            } else if ("LoadAware".equalsIgnoreCase(algorithm)) {
                return new QCloudBrokerLoadAware("QCloudBrokerLoadAware");
            } else if ("QCLAR".equalsIgnoreCase(algorithm)) {
                return new QCloudBrokerQCLAR("QCloudBrokerQCLAR");
            } else if ("QCLAAR".equalsIgnoreCase(algorithm)) {
                return new QCloudBrokerQCLAAR("QCloudBrokerQCLAAR");
            } else {
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
            } else {
                // Lottery, LoadAware, QCLAR, QCLAAR all keep original edge broker
                return new QEdgeBroker("QEdgeBroker");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create edge broker for " + algorithm, e);
        }
    }

    private static List<QTask> createQTaskList(QBroker qBroker) {
        List<QTask> qTaskList = new ArrayList<QTask>();

        Path datasetPath = Paths.get(System.getProperty("user.dir"), DATASET_PATH);
        QTaskImporter importer = new QTaskImporter();

        try {
            List<QTask> importedQTasks = importer.importQTasksFromCsv(datasetPath.toString());

            for (QTask qtask : importedQTasks) {
                qtask.setBrokerId(qBroker.getId());
                qtask.setQNodeId(-1);
                qTaskList.add(qtask);
            }
        } catch (IOException e) {
            System.err.println("Error reading CSV file: " + e.getMessage());
        }

        return qTaskList;
    }

    private static QEdgeDatacenter createQEDatacenter(String name) {
        String[] backendNames = {
                "ibm_hanoi",
                "ibm_auckland",
                "ibm_cairo",
                "ibmq_mumbai",
                "ibmq_kolkata"
        };

        int[] nodeIds = {11, 12, 13, 14, 15};

        List<QNode> qeNodeList = new ArrayList<QNode>();

        for (int i = 0; i < nodeIds.length; i++) {
            QNode qNode = IBMQNode.createNode(
                    nodeIds[i],
                    backendNames[i],
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
        int[] nodeIds = {21, 22, 23, 24, 25, 26};
        String backendName = "ibm_washington";

        // Upgraded graded heterogeneity
        int[] clopsValues = {220, 320, 450, 700, 1000, 1400};
        int[] qvValues    = {32, 48, 64, 96, 128, 192};

        List<QNode> qcNodeList = new ArrayList<QNode>();

        for (int i = 0; i < nodeIds.length; i++) {
            QNode qcNode = IBMQNode.createNode(
                    nodeIds[i],
                    backendName,
                    new QTaskSchedulerSpaceShared()
            );

            qcNode.setCLOPS(clopsValues[i]);
            qcNode.setQuantumVolume(qvValues[i]);

            qcNodeList.add(qcNode);
        }

        double timeZone = 0.0;
        double costPerSec = 1.6;

        QDatacenterCharacteristics characteristics =
                new QDatacenterCharacteristics(qcNodeList, timeZone, costPerSec);

        return new QCloudDatacenter(name, characteristics);
    }
}