package org.iquantum.gateways;

import org.iquantum.brokers.CEdgeBroker;
import org.iquantum.brokers.QEdgeBroker;
import org.iquantum.core.SimEvent;
import org.iquantum.core.iQuantum;
import org.iquantum.core.iQuantumTags;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;

import java.util.List;

public class EdgeGatewayLoadAware extends EdgeGateway {

    public EdgeGatewayLoadAware(String name,
                                CEdgeBroker cBroker,
                                QEdgeBroker qBroker,
                                CloudGateway cloudGateway) throws Exception {

        super(name, cBroker, qBroker, cloudGateway);
    }

    @Override
    public void processEvent(SimEvent ev) {

        switch (ev.getTag()) {

            case iQuantumTags.CLOUD_GATEWAY_DISPATCH_TASK:
                processTaskDispatch(ev);
                break;

            case iQuantumTags.OFFLOAD_QTASK_FROM_EDGE:
                processQTaskOffload(ev);
                break;

            default:
                super.processEvent(ev);
        }
    }

    private void processQTaskOffload(SimEvent ev) {

        List<QTask> offloadedTasks = (List<QTask>) ev.getData();

        Log.printConcatLine(
                iQuantum.clock(),
                ": ",
                getName(),
                " forwarding ",
                offloadedTasks.size(),
                " QTasks to Cloud Gateway"
        );

        sendNow(
                cloudGateway.getId(),
                iQuantumTags.OFFLOAD_QTASK_FROM_EDGE,
                offloadedTasks
        );
    }
}