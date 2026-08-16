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
 * Quantum Cloud Broker with Load-Aware backend selection.
 *
 * Policy:
 * - Build a broker-side predicted load snapshot for each QNode
 *   using: runningQTasks + waitingQTasks
 * - For each incoming QTask, first filter feasible candidate QNodes
 *   using preScheduleQTask(...)
 * - Select the candidate with minimum predicted load
 * - Break ties deterministically using smaller QNode id
 *
 * Important:
 * - This class preserves the original QCloudBroker offload behavior.
 * - DO NOT override processQTaskOffload() here.
 */
public class QCloudBrokerLoadAware extends QCloudBroker {

    public QCloudBrokerLoadAware(String name) throws Exception {
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

        // Broker-side predicted load snapshot
        Map<Integer, Integer> predictedLoadMap = new HashMap<Integer, Integer>();
        for (QNode node : qNodeList) {
            int initialLoad = node.getQTaskScheduler().runningQTasks()
                    + node.getQTaskScheduler().getQTaskWaitingList().size();
            predictedLoadMap.put(node.getId(), initialLoad);
        }

        for (QTask qTask : getQTaskList()) {

            QNode qNode = null; // reset per task

            if (qTask.getQNodeId() == -1) {
                List<? extends QNode> candidateNodes =
                        preScheduleQTask((List<QNode>) qNodeList, qTask);

                if (!candidateNodes.isEmpty()) {
                    qNode = selectLeastLoadedNode(candidateNodes, predictedLoadMap);
                    qTask.setQNodeId(qNode.getId());
                }
            } else {
                // preserve original direct-node behavior
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
                        Log.printConcatLine(
                                iQuantum.clock(), ": ", getName(),
                                ": Sending QTask ", qTask.getQTaskId(),
                                " to QNode #", qNode.getId(),
                                " [predictedLoad=", predictedLoadMap.get(qNode.getId()), "]"
                        );
                    }

                    sendNow(
                            qNode.getQDatacenter().getId(),
                            iQuantumTags.QTASK_SUBMIT,
                            qTask
                    );

                    numQTaskSubmitted++;
                    submittedQTasks.add(qTask);

                    // Update broker-side predicted load immediately
                    int newLoad = predictedLoadMap.get(qNode.getId()) + 1;
                    predictedLoadMap.put(qNode.getId(), newLoad);
                }
            } else {
                if (!Log.isDisabled()) {
                    Log.printConcatLine(
                            iQuantum.clock(), ": ", getName(),
                            ": Postponing execution of QTask ", qTask.getQTaskId(),
                            ": No sufficient QNode available."
                    );
                }

                failedQTasks.add(qTask);
                numQTaskFailed++;
            }
        }

        getQTaskList().removeAll(submittedQTasks);
        getQTaskList().removeAll(failedQTasks);
    }

    /**
     * Select the feasible QNode with minimum predicted load.
     * Tie-breaker: smaller QNode id for deterministic behavior.
     */
    private QNode selectLeastLoadedNode(
            List<? extends QNode> candidateNodes,
            Map<Integer, Integer> predictedLoadMap
    ) {
        QNode bestNode = null;
        int minLoad = Integer.MAX_VALUE;

        for (QNode node : candidateNodes) {
            int load = predictedLoadMap.containsKey(node.getId())
                    ? predictedLoadMap.get(node.getId())
                    : Integer.MAX_VALUE;

            if (bestNode == null
                    || load < minLoad
                    || (load == minLoad && node.getId() < bestNode.getId())) {
                bestNode = node;
                minLoad = load;
            }
        }

        return bestNode;
    }
}