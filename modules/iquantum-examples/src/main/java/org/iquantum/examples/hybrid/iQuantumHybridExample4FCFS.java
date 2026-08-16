/**
 * iQuantum Cloud Edge Example
 * This example demonstrates the use of iQuantum in a hybrid cloud-edge environment.
 * It creates a CEDatacenter, a CCDatacenter, a QEDatacenter, and a QDatacenter, and then creates all respective brokers
 * and gateways for the cloud and edge environments.
 * It also creates a CTask and a QTask to be submitted to the CCloudBroker and QCloudBroker respectively.
 * Finally, it starts the simulation and prints the results.
 */

package org.iquantum.examples.hybrid;

import org.iquantum.backends.quantum.IBMQNode;
import org.iquantum.backends.quantum.QNode;
import org.iquantum.brokers.*;
import org.iquantum.core.iQuantum;
import org.iquantum.datacenters.QCloudDatacenter;
import org.iquantum.datacenters.QDatacenterCharacteristics;
import org.iquantum.datacenters.QEdgeDatacenter;
import org.iquantum.gateways.CloudGateway;
import org.iquantum.gateways.CloudGatewayLoadAware;
import org.iquantum.gateways.EdgeGateway;
import org.iquantum.gateways.EdgeGatewayLoadAware;
import org.iquantum.policies.qtasks.QTaskSchedulerSpaceShared;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;
import org.iquantum.utils.QTaskExporter;
import org.iquantum.utils.QTaskImporter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.io.PrintStream;
import java.io.FileOutputStream;


public class iQuantumHybridExample4FCFS {
    private static List<QTask> qTaskList;

    private static  List<QNode> qcNodeList;

    private static  List<QNode> qeNodeList;
    public static void main(String[] args) throws Exception {
        //        Log.disable();
        // Get the current time before executing the Java code
        long startTime = System.currentTimeMillis();
        String exampleName = "iQuantumCloudEdgeExample4_LoadAware";
        System.out.println("Start the " + exampleName + " simulation");

        try {
            PrintStream fileOut = new PrintStream(new FileOutputStream("iQuantumCloudEdgeExample4_LoadAwareV4.txt"));
            System.setOut(fileOut);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Step 1: Initialize the core simulation package. It should be called before creating any entities.
        int num_user = 1;
        Calendar calendar = Calendar.getInstance();
        boolean trace_flag = true;  // trace events
        iQuantum.init(num_user, calendar, trace_flag);

        // Step 2: Create QDatacenter

        QCloudDatacenter QCDatacenter = createQCDatacenter("QCloudDatacenter");
        QEdgeDatacenter QEDatacenter = createQEDatacenter("QEdgeDatacenter");

        // Step 3: Create a CBroker and a QBroker
        CCloudBroker ccBroker = createBroker();
        CEdgeBroker ceBroker = createEBroker();

        QCloudBrokerFCFS qcBroker = createQCBroker();
        QEdgeBroker qeBroker = createQEBroker();

        CloudGateway cloudGateway = new CloudGatewayLoadAware("CloudGateway", ccBroker, qcBroker);
        EdgeGateway edgeGateway = new EdgeGatewayLoadAware("EdgeGateway", ceBroker, qeBroker, cloudGateway);

        // Step 6: Create 4 QTasks
        qTaskList = createQTaskList(qeBroker);

        // Step 7: Submit all tasks to brokers
        edgeGateway.submitQTasks(qTaskList);

        // Step 8: Start the simulation
        iQuantum.startSimulation();

        // Step 9: Stop the simulation
        iQuantum.stopSimulation();

        // Step 10: Print the results when simulation is over
        Log.printLine("SIMULATION RESULTS");
        Log.printLine("==========================================================");
        List<QTask> qcTaskResults = qcBroker.getQTaskReceivedList();
        Log.printLine();
        Log.printLine("CLOUD Layer ================================");
        if(qcTaskResults.size() == 0) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qcTaskResults);
            QTaskExporter.extractQTaskListToCSV(qcTaskResults, exampleName+"-cloud");
        }

        Log.printLine();
        List<QTask> qeTaskResults = qeBroker.getQTaskReceivedList();
        Log.printLine("EDGE Layer ================================");
        if(qeTaskResults.size() == 0) {
            Log.printLine("No QTask received");
        } else {
            QTaskExporter.printQTaskList(qeTaskResults);
            QTaskExporter.extractQTaskListToCSV(qeTaskResults, exampleName+"-edge");
        }
        Log.printLine(exampleName +" finished!");

        // ----- RESOURCE CONSUMPTION
        // Get the current time after executing the Java code
        long endTime = System.currentTimeMillis(); // or System.nanoTime();

        // Calculate the execution time
        long executionTime = endTime - startTime;

        // Print the execution time in milliseconds (for System.currentTimeMillis()) or nanoseconds (for System.nanoTime())
        System.out.println("Execution time: " + executionTime + " milliseconds");
        // Get the OperatingSystemMXBean instance
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        // Get the MemoryMXBean instance
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        // Get the number of available processors (CPU cores)
        int availableProcessors = osBean.getAvailableProcessors();
        System.out.println("Number of available processors (CPU cores): " + availableProcessors);

        // Print CPU usage information
        double cpuUsage = osBean.getSystemLoadAverage();
        System.out.println("CPU usage: " + cpuUsage + " (load average)");

        // Print RAM (memory) usage information
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        System.out.println("Used memory: " + usedMemory / (1024L * 1024L) + " MB");
        System.out.println("Max memory: " + maxMemory / (1024L * 1024L) + " MB");
    }

    /**
     * Creates the broker.
     *
     * @return the datacenter broker
     */
    private static CCloudBroker createBroker() {
        CCloudBroker broker = null;
        try {
            broker = new CCloudBroker("CCBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    private static CEdgeBroker createEBroker() {
        CEdgeBroker broker = null;
        try {
            broker = new CEdgeBroker("CEBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    /**
     * QUANTUM PART
     */

    private static List<QTask> createQTaskList(QBroker qBroker) {
        List<QTask> QTaskList = new ArrayList<>();
//        String folderPath = "dataset/iquantum/MQT-Set01-298-10-27-IBMQ27-Opt3-Extra.csv";
//        String folderPath = "dataset/iquantum/MQT-Set02-10-27-Mapped-AllAlgorithmLeft-Extra.csv";
//        String folderPath = "dataset/iquantum/MQT-Set03-7-127-AllOpt-IBMMapped-Only127-Extra.csv";
        String folderPath = "dataset/iquantum/MQT-Set04-7-127-AllOpt-IBMMapped-Extra.csv";
        Path datasetPath = Paths.get(System.getProperty("user.dir"), folderPath);
        QTaskImporter QTaskImporter = new QTaskImporter();
        try {
            List<QTask> QTasks = QTaskImporter.importQTasksFromCsv(datasetPath.toString());
            for (QTask qtask : QTasks) {
                qtask.setBrokerId(qBroker.getId());
                qtask.setQNodeId(-1);   // important
                QTaskList.add(qtask);
            }
        } catch (IOException e) {
            System.err.println("Error reading CSV file: " + e.getMessage());
        }
        return QTaskList;
    }

    /**
     * Create a QBroker
     * @return QBroker
     */
    private static QCloudBrokerFCFS createQCBroker() {
        QCloudBrokerFCFS qBroker = null;
        try {
            qBroker = new QCloudBrokerFCFS("QCloudBrokerLoadAware");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return qBroker;
    }

    private static QEdgeBroker createQEBroker() {
        QEdgeBroker qBroker = null;
        try {
            qBroker = new QEdgeBroker("QEBroker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return qBroker;
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

        qeNodeList = new ArrayList<>();

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

        List<QNode> qcNodeList = new ArrayList<>();

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

