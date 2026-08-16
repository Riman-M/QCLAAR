package org.iquantum.brokers;

import org.iquantum.backends.quantum.QNode;
import org.iquantum.core.SimEvent;
import org.iquantum.core.iQuantum;
import org.iquantum.core.iQuantumTags;
import org.iquantum.lists.QNodeList;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Q-CLAAR at Edge:
 * Quantum Capability, Load, and Aging-Aware Routing
 *
 * score = alpha * loadNorm + beta * execNorm - gamma * capabilityNorm + lambda * agingNorm
 */
public class QEdgeBrokerQCLAAR extends QEdgeBroker {

    private static final double ALPHA  = 0.40;
    private static final double BETA   = 0.30;
    private static final double GAMMA  = 0.15;
    private static final double LAMBDA = 0.15;
    private static final double EPS    = 1e-9;

    public QEdgeBrokerQCLAAR(String name) throws Exception {
        super(name);
    }

    @Override
    protected void processQTaskSubmit(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int qDatacenter = data[0];

        List<QTask> submittedQTasks = new ArrayList<QTask>();
        List<QTask> failedQTasks = new ArrayList<QTask>();

        Log.printConcatLine(
                iQuantum.clock(), ": ", getName(),
                " : Started scheduling all QTasks to QDatacenter #", qDatacenter
        );

        List<? extends QNode> qNodeList =
                getQDatacenterCharacteristicsList().get(qDatacenter).getQNodeList();
        setQNodeList(qNodeList);

        Map<Integer, Integer> predictedLoadMap = new HashMap<Integer, Integer>();
        for (QNode node : qNodeList) {
            int initialLoad = node.getQTaskScheduler().runningQTasks()
                    + node.getQTaskScheduler().getQTaskWaitingList().size();
            predictedLoadMap.put(node.getId(), initialLoad);
        }

        for (QTask qTask : getQTaskList()) {

            QNode qNode = null;

            if (qTask.getQNodeId() == -1) {
                List<? extends QNode> candidateNodes =
                        preScheduleQTask((List<QNode>) qNodeList, qTask);

                if (!candidateNodes.isEmpty()) {
                    qNode = selectBestNodeByCompositeScore(
                            candidateNodes,
                            qTask,
                            predictedLoadMap
                    );
                    qTask.setQNodeId(qNode.getId());
                }
            } else {
                qNode = QNodeList.getById(getQNodeList(), qTask.getQNodeId());

                if (qNode == null) {
                    if (!Log.isDisabled()) {
                        Log.printConcatLine(
                                iQuantum.clock(), ": ", getName(),
                                ": Postponing execution of QTask ", qTask.getQTaskId(),
                                ": QNode is not available"
                        );
                    }
                    continue;
                }
            }

            if (qNode != null) {
                if (!Log.isDisabled()) {
                    Log.printConcatLine(
                            iQuantum.clock(), ": ", getName(),
                            ": Checking if QNode #", qNode.getId(),
                            " has enough qubits/gates to execute QTask",
                            qTask.getQTaskId()
                    );
                }

                if (verifyConstraints(qNode, qTask, submittedQTasks)) {
                    if (!Log.isDisabled()) {
                        double score = computeCompositeScore(
                                qNode,
                                qTask,
                                predictedLoadMap,
                                getQNodeList()
                        );

                        Log.printConcatLine(
                                iQuantum.clock(), ": ", getName(),
                                ": Sending QTask ", qTask.getQTaskId(),
                                " to QNode #", qNode.getId(),
                                " [predictedLoad=", predictedLoadMap.get(qNode.getId()),
                                ", waiting=", qNode.getQTaskScheduler().getQTaskWaitingList().size(),
                                ", score=", String.format("%.4f", score), "]"
                        );
                    }

                    sendNow(
                            qNode.getQDatacenter().getId(),
                            iQuantumTags.QTASK_SUBMIT,
                            qTask
                    );

                    numQTaskSubmitted++;
                    submittedQTasks.add(qTask);

                    int newLoad = predictedLoadMap.get(qNode.getId()) + 1;
                    predictedLoadMap.put(qNode.getId(), newLoad);
                } else {
                    if (!Log.isDisabled()) {
                        Log.printConcatLine(
                                iQuantum.clock(), ": ", getName(),
                                ": Try offloading QTask to Cloud ", qTask.getQTaskId(),
                                ": No sufficient QNode at Edge layer is available."
                        );
                    }

                    qTask.setBrokerId(-1);
                    failedQTasks.add(qTask);
                    numQTaskFailed++;
                }
            } else {
                if (!Log.isDisabled()) {
                    Log.printConcatLine(
                            iQuantum.clock(), ": ", getName(),
                            ": Postponing execution of QTask ", qTask.getQTaskId(),
                            ": No sufficient QNode available."
                    );
                    Log.printConcatLine(
                            iQuantum.clock(), ": ", getName(),
                            ": Try offloading QTask to Cloud ", qTask.getQTaskId(),
                            ": No sufficient QNode at Edge layer is available."
                    );
                }

                qTask.setBrokerId(-1);
                failedQTasks.add(qTask);
                numQTaskFailed++;
            }
        }

        sendNow(
                getCloudGateway().getId(),
                iQuantumTags.OFFLOAD_QTASK_FROM_EDGE,
                failedQTasks
        );

        getQTaskList().removeAll(submittedQTasks);
        getQTaskList().removeAll(failedQTasks);
    }

    private QNode selectBestNodeByCompositeScore(
            List<? extends QNode> candidateNodes,
            QTask qTask,
            Map<Integer, Integer> predictedLoadMap
    ) {
        QNode bestNode = null;
        double bestScore = Double.MAX_VALUE;

        for (QNode node : candidateNodes) {
            double score = computeCompositeScore(node, qTask, predictedLoadMap, candidateNodes);

            if (bestNode == null) {
                bestNode = node;
                bestScore = score;
                continue;
            }

            int currentLoad = predictedLoadMap.containsKey(node.getId())
                    ? predictedLoadMap.get(node.getId()) : Integer.MAX_VALUE;
            int bestLoad = predictedLoadMap.containsKey(bestNode.getId())
                    ? predictedLoadMap.get(bestNode.getId()) : Integer.MAX_VALUE;

            if (score < bestScore
                    || (Math.abs(score - bestScore) < EPS && currentLoad < bestLoad)
                    || (Math.abs(score - bestScore) < EPS
                    && currentLoad == bestLoad
                    && node.getId() < bestNode.getId())) {
                bestNode = node;
                bestScore = score;
            }
        }

        return bestNode;
    }

    private double computeCompositeScore(
            QNode node,
            QTask qTask,
            Map<Integer, Integer> predictedLoadMap,
            List<? extends QNode> referenceNodes
    ) {
        double loadNorm = normalizeLoad(node, predictedLoadMap, referenceNodes);
        double execNorm = normalizeExecutionProxy(node, qTask, referenceNodes);
        double capabilityNorm = normalizeCapability(node, referenceNodes);
        double agingNorm = normalizeAging(node, referenceNodes);

        return (ALPHA * loadNorm)
                + (BETA * execNorm)
                - (GAMMA * capabilityNorm)
                + (LAMBDA * agingNorm);
    }

    private double normalizeLoad(
            QNode node,
            Map<Integer, Integer> predictedLoadMap,
            List<? extends QNode> referenceNodes
    ) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double value = predictedLoadMap.containsKey(node.getId())
                ? predictedLoadMap.get(node.getId()) : 0.0;

        for (QNode ref : referenceNodes) {
            double load = predictedLoadMap.containsKey(ref.getId())
                    ? predictedLoadMap.get(ref.getId()) : 0.0;
            min = Math.min(min, load);
            max = Math.max(max, load);
        }

        return normalize(value, min, max);
    }

    private double normalizeExecutionProxy(
            QNode node,
            QTask qTask,
            List<? extends QNode> referenceNodes
    ) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double value = executionProxy(node, qTask);

        for (QNode ref : referenceNodes) {
            double proxy = executionProxy(ref, qTask);
            min = Math.min(min, proxy);
            max = Math.max(max, proxy);
        }

        return normalize(value, min, max);
    }

    private double normalizeCapability(
            QNode node,
            List<? extends QNode> referenceNodes
    ) {
        double minClops = Double.MAX_VALUE;
        double maxClops = -Double.MAX_VALUE;
        double minQv = Double.MAX_VALUE;
        double maxQv = -Double.MAX_VALUE;

        for (QNode ref : referenceNodes) {
            double clops = safeClops(ref);
            double qv = safeQuantumVolume(ref);

            minClops = Math.min(minClops, clops);
            maxClops = Math.max(maxClops, clops);
            minQv = Math.min(minQv, qv);
            maxQv = Math.max(maxQv, qv);
        }

        double clopsNorm = normalize(safeClops(node), minClops, maxClops);
        double qvNorm = normalize(safeQuantumVolume(node), minQv, maxQv);

        return 0.5 * clopsNorm + 0.5 * qvNorm;
    }

    private double normalizeAging(
            QNode node,
            List<? extends QNode> referenceNodes
    ) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double value = node.getQTaskScheduler().getQTaskWaitingList().size();

        for (QNode ref : referenceNodes) {
            double waiting = ref.getQTaskScheduler().getQTaskWaitingList().size();
            min = Math.min(min, waiting);
            max = Math.max(max, waiting);
        }

        return normalize(value, min, max);
    }

    private double executionProxy(QNode node, QTask qTask) {
        double clops = safeClops(node);
        double layers = Math.max(1.0, qTask.getNumLayers());
        double shots = Math.max(1.0, qTask.getNumShots());
        return (layers * shots) / clops;
    }

    private double safeClops(QNode node) {
        double clops = node.getCLOPS();
        return clops > 0 ? clops : 1.0;
    }

    private double safeQuantumVolume(QNode node) {
        double qv = node.getQuantumVolume();
        return qv > 0 ? qv : 1.0;
    }

    private double normalize(double value, double min, double max) {
        if (Math.abs(max - min) < EPS) {
            return 0.0;
        }
        return (value - min) / (max - min);
    }
}