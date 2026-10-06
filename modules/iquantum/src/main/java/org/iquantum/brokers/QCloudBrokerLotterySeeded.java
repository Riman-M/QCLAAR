package org.iquantum.brokers;

import org.iquantum.backends.quantum.QNode;
import org.iquantum.core.SimEvent;
import org.iquantum.core.iQuantum;
import org.iquantum.core.iQuantumTags;
import org.iquantum.datacenters.CDatacenterCharacteristics;
import org.iquantum.datacenters.CEdgeDatacenter;
import org.iquantum.datacenters.QCloudDatacenter;
import org.iquantum.datacenters.QDatacenterCharacteristics;
import org.iquantum.lists.QNodeList;
import org.iquantum.policies.qtasks.QNodeSelectionLottery;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.iquantum.policies.qtasks.QNodeSelectionLotterySeeded;

/**
 * Lottery baseline with a seeded random generator, for reproducible
 * replications. Identical to QCloudBroker except that node selection
 * draws from QNodeSelectionLotterySeeded.RNG instead of an unseeded Random.
 */
public class QCloudBrokerLotterySeeded extends QCloudBroker {

    public QCloudBrokerLotterySeeded(String name) throws Exception {
        super(name);
    }

    @Override
    protected void processQTaskSubmit(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int qDatacenter = data[0];
        List<QTask> submittedQTasks = new ArrayList<QTask>();
        List<QTask> failedQTasks = new ArrayList<QTask>();
        Log.printConcatLine(iQuantum.clock(), ": ", getName(), " : Started scheduling all QTasks to QDatacenter #", qDatacenter);

        List<? extends QNode> qNodeList = getQDatacenterCharacteristicsList().get(qDatacenter).getQNodeList();
        setQNodeList(qNodeList);
        QNodeSelectionLotterySeeded qnodeselection = new QNodeSelectionLotterySeeded(0.5, 0.5);
        QNode qNode = null;
        for (QTask qTask : getQTaskList()) {
            qNode = null;
            if (qTask.getQNodeId() == -1) {
                // APPLY SIMPLE LOTTERY BACKEND SELECTION STRATEGY
                List<? extends QNode> preQNodes;
                preQNodes = preScheduleQTask((List<QNode>) qNodeList, qTask);
                if(preQNodes.size() > 1) {
                    qNode = qnodeselection.selectQNode(preQNodes);
                    qTask.setQNodeId(qNode.getId());
                }
                else if(preQNodes.size() == 1) {
                    qNode = preQNodes.get(0);
                    qTask.setQNodeId(qNode.getId());
                }
            } else {
                // Submit qtask to a specific QNode
                qNode = QNodeList.getById(getQNodeList(), qTask.getQNodeId());
                if (qNode == null) {
                    if(!Log.isDisabled()) {
                        Log.printConcatLine(iQuantum.clock(), ": ", getName(), ": Postponing execution of QTask ", qTask.getQTaskId(),
                                ": QNode is not available");
                    }
                    continue;
                }
            }
            /** QNode must have enough resources to execute the QTask
             * number of qubits of QNode >= number of qubits of QTask
             */
            if (qNode!=null) {
                if (!Log.isDisabled()) {
                    Log.printConcatLine(iQuantum.clock(), ": ", getName(), ": Checking if QNode #", qNode.getId(), " has enough qubits/gates to execute QTask",
                            qTask.getQTaskId());
                }
                if(verifyConstraints(qNode, qTask, submittedQTasks)) {
                    if (!Log.isDisabled()) {
                        Log.printConcatLine(iQuantum.clock(), ": ", getName(), ": Sending QTask ",
                                qTask.getQTaskId(), " to QNode #", qNode.getId());
                    }
                    sendNow(qNode.getQDatacenter().getId(), iQuantumTags.QTASK_SUBMIT, qTask);
                    numQTaskSubmitted++;
                    submittedQTasks.add(qTask);
                }
            } else
            {
                if(!Log.isDisabled()) {
                    Log.printConcatLine(iQuantum.clock(), ": ", getName(), ": Postponing execution of QTask ", qTask.getQTaskId(),
                            ": No sufficient QNode available.");
                }
                failedQTasks.add(qTask);
                numQTaskFailed++;
                // If this task is submitted to Edge Layer, it can be offloaded to Cloud Layer
            }
        }
        getQTaskList().removeAll(submittedQTasks);
        getQTaskList().removeAll(failedQTasks);
    }
}
