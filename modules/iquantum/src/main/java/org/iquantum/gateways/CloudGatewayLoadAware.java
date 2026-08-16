package org.iquantum.gateways;

import org.iquantum.brokers.CCloudBroker;
import org.iquantum.brokers.QCloudBroker;
import org.iquantum.core.SimEvent;
import org.iquantum.core.iQuantum;
import org.iquantum.core.iQuantumTags;
import org.iquantum.tasks.QTask;
import org.iquantum.utils.Log;

import java.util.ArrayList;
import java.util.List;

public class CloudGatewayLoadAware extends CloudGateway {

    public CloudGatewayLoadAware(String name, CCloudBroker cBroker, QCloudBroker qBroker) throws Exception {
        super(name, cBroker, qBroker);
    }

    public CloudGatewayLoadAware(String name, QCloudBroker qBroker) throws Exception {
        super(name, qBroker);
    }

    public CloudGatewayLoadAware(String name, CCloudBroker cBroker) throws Exception {
        super(name, cBroker);
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case iQuantumTags.CLOUD_GATEWAY_DISPATCH_TASK:
                processTaskDispatch(ev);
                break;

            case iQuantumTags.OFFLOAD_QTASK_FROM_EDGE:
                processQTaskOffloadExperimental(ev);
                break;

            default:
                Log.printConcatLine(getName(), ": unknown event type at CloudGatewayLoadAware");
        }
    }

    private void processQTaskOffloadExperimental(SimEvent ev) {
        List<QTask> offloadedTasks = (List<QTask>) ev.getData();

        Log.printConcatLine(
                iQuantum.clock(), ": ", getName(),
                " : Offloading ", offloadedTasks.size(),
                " QTasks from Edge Gateway to Cloud Gateway"
        );

        getQTaskList().addAll(offloadedTasks);

        if (qBroker != null) {
            qBroker.submitQTaskList(offloadedTasks);
        }
    }
}