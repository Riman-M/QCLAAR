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
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class iQuantumHybridExample4QCLAR {

    private static List<QTask> qTaskList;
    private static List<QNode> qcNodeList;
    private static List<QNode> qeNodeList;

    public static void main(String[] args) throws Exception {
//        Log.disable();

        try {
            PrintStream fileOut = new PrintStream(
                    new FileOutputStream("iQuantumCloudEdgeExample4QCLAR.txt")
            );
            System.setOut(fileOut);
        } catch (Exception e) {
            e.printStackTrace();
        }

        long startTime = System.currentTimeMillis();
        String exampleName = "iQuantumCloudEdgeExample4QCLAR";
        System.out.println("Start the " + exampleName + " simulation");

        int num_user = 1;
        Calendar calendar = Calendar.getInstance();
        boolean trace_flag = true;
        iQuantum.init(num_user, calendar, trace_flag);

        QCloudDatacenter qcDatacenter = createQCDatacenter("QCloudDatacenter");
        QEdgeDatacenter qeDatacenter = createQEDatacenter("QEdgeDatacenter");

        CCloudBroker ccBroker = createBroker();
        CEdgeBroker ceBroker = createEBroker();

        // Load-aware only at cloud side
        QCloudBrokerQCLAR qcBroker = createQCBroker();

        // Keep original edge broker unchanged
        QEdgeBroker qeBroker = createQEBroker();

        // Keep original gateways unchanged
        CloudGateway cloudGateway = new CloudGateway("CloudGateway", ccBroker, qcBroker);
        EdgeGateway edgeGateway = new EdgeGateway("EdgeGateway", ceBroker, qeBroker, cloudGateway);

        qTaskList = createQTaskList(qeBroker);

        edgeGateway.submitQTasks(qTaskList);

        iQuantum.startSimulation();
        iQuantum.stopSimulation();

        Log.printLine("SIMULATION RESULTS");
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

        Log.printLine();
        List<QTask> qeTaskResults = qeBroker.getQTaskReceivedList();
        Log.printLine("EDGE Layer ================================");
        if (qeTaskResults.size() == 0) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qeTaskResults);
            QTaskExporter.extractQTaskListToCSV(qeTaskResults, exampleName + "-edge");
        }

        Log.printLine(exampleName + " finished!");

        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;

        System.out.println("Execution time: " + executionTime + " milliseconds");

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        int availableProcessors = osBean.getAvailableProcessors();
        System.out.println("Number of available processors (CPU cores): " + availableProcessors);

        double cpuUsage = osBean.getSystemLoadAverage();
        System.out.println("CPU usage: " + cpuUsage + " (load average)");

        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        System.out.println("Used memory: " + usedMemory / (1024L * 1024L) + " MB");
        System.out.println("Max memory: " + maxMemory / (1024L * 1024L) + " MB");
    }

    private static CCloudBroker createBroker() {
        try {
            return new CCloudBroker("CCBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static CEdgeBroker createEBroker() {
        try {
            return new CEdgeBroker("CEBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static List<QTask> createQTaskList(QBroker qBroker) {
        List<QTask> qTaskList = new ArrayList<QTask>();

//        String folderPath = "dataset/iquantum/MQT-Set01-298-10-27-IBMQ27-Opt3-Extra.csv";
//        String folderPath = "dataset/iquantum/MQT-Set02-10-27-Mapped-AllAlgorithmLeft-Extra.csv";
//        String folderPath = "dataset/iquantum/MQT-Set03-7-127-AllOpt-IBMMapped-Only127-Extra.csv";
        String folderPath = "dataset/iquantum/MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv";

        Path datasetPath = Paths.get(System.getProperty("user.dir"), folderPath);
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

    private static QCloudBrokerQCLAR createQCBroker() {
        try {
            return new QCloudBrokerQCLAR("QCloudBrokerQCLAR");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static QEdgeBroker createQEBroker() {
        try {
            return new QEdgeBroker("QEBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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

        qeNodeList = new ArrayList<QNode>();

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
        int quantumVolume = 128;
        int clops = 850;

        qcNodeList = new ArrayList<QNode>();

        for (int nodeId : nodeIds) {
            QNode qcNode = IBMQNode.createNode(
                    nodeId,
                    backendName,
                    new QTaskSchedulerSpaceShared()
            );

            qcNodeList.add(qcNode);

            if (nodeId >= 24) {
                qcNode.setQuantumVolume(quantumVolume);
                qcNode.setCLOPS(clops);
            }
        }

        double timeZone = 0.0;
        double costPerSec = 1.6;

        QDatacenterCharacteristics characteristics =
                new QDatacenterCharacteristics(qcNodeList, timeZone, costPerSec);

        return new QCloudDatacenter(name, characteristics);
    }
}