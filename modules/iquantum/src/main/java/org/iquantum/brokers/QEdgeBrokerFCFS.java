package org.iquantum.brokers;

import org.iquantum.backends.quantum.QNode;
import org.iquantum.core.SimEvent;
import org.iquantum.core.iQuantum;
import org.iquantum.core.iQuantumTags;
import org.iquantum.lists.QNodeList;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Quantum Edge Broker with FCFS / First-Feasible backend selection.
 *
 * Policy:
 * - If a QTask is not pre-bound to a QNode (qNodeId == -1),
 *   select the first feasible QNode returned by preScheduleQTask(...).
 * - If a QTask is pre-bound, preserve the original direct-node behavior.
 *
 * This class preserves the original QEdgeBroker offload flow:
 * failed edge tasks are sent to the configured CloudGateway.
 */
public class QEdgeBrokerFCFS extends QEdgeBroker {

    public QEdgeBrokerFCFS(String name) throws Exception {
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

        QNode qNode = null;

        for (QTask qTask : getQTaskList()) {

            qNode = null; // reset per task

            if (qTask.getQNodeId() == -1) {
                // FCFS / First-Feasible backend selection
                List<? extends QNode> preQNodes =
                        preScheduleQTask((List<QNode>) qNodeList, qTask);

                if (!preQNodes.isEmpty()) {
                    qNode = preQNodes.get(0); // first feasible candidate
                    qTask.setQNodeId(qNode.getId());
                }
            } else {
                // Submit QTask to a specific QNode
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
                                " to QNode #", qNode.getId()
                        );
                    }

                    sendNow(
                            qNode.getQDatacenter().getId(),
                            iQuantumTags.QTASK_SUBMIT,
                            qTask
                    );

                    numQTaskSubmitted++;
                    submittedQTasks.add(qTask);
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

        // Preserve original edge-to-cloud offload behavior
        sendNow(
                getCloudGateway().getId(),
                iQuantumTags.OFFLOAD_QTASK_FROM_EDGE,
                failedQTasks
        );

        getQTaskList().removeAll(submittedQTasks);
        getQTaskList().removeAll(failedQTasks);
    }
}