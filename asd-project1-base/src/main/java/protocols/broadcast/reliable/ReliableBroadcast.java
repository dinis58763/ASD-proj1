package protocols.broadcast.reliable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.broadcast.common.BroadcastRequest;
import protocols.broadcast.common.DeliverNotification;
import protocols.broadcast.reliable.messages.GossipMessage;
import protocols.broadcast.reliable.messages.PullGossipMessage;
import protocols.membership.common.notifications.ChannelCreated;
import protocols.membership.common.notifications.NeighbourDown;
import protocols.membership.common.notifications.NeighbourUp;
import protocols.membership.full.timers.InfoTimer;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.data.Host;

public class ReliableBroadcast extends GenericProtocol {

    //Protocol information, to register in babel
    public static final String PROTOCOL_NAME = "Reliable";
    public static final short PROTOCOL_ID = 200;
    private static final Logger logger = LogManager.getLogger(ReliableBroadcast.class);
    private final Host myself; //My own address/port
    private final Set<Host> pi; // set of processes you send a message to
    private final Set<UUID> delivered; // //Ids of messages already delivered
    // Map to keep track of delivered messages by neighbour
    private final Map<Host, Set<UUID>> deliveredMsgsMap;
    // Map to keep track of the messages' content according by mid
    private final Map<UUID, byte[]> msgContentMap;
    // Create a ScheduledExecutorService
    ScheduledExecutorService scheduler;
    private final int t; // number of recipients or neighbors to which a message is sent or forwarded by a process
    //We can only start sending messages after the membership protocol informed us that the channel is ready
    private boolean channelReady;
    private Host gossipTarget;
    private int nMessagesReceived;

    public ReliableBroadcast(Properties properties, Host myself) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.myself = myself;
        t = 1;
        pi = new HashSet<>();
        delivered = new HashSet<>();
        channelReady = false;
        deliveredMsgsMap = new HashMap<>();
        msgContentMap = new HashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);

        nMessagesReceived = 0;

        /*--------------------- Register Request Handlers -----------------------------*/
        registerRequestHandler(BroadcastRequest.REQUEST_ID, this::uponBroadcastRequest);

        /*--------------------- Register Notification Handlers ----------------------------- */
        subscribeNotification(NeighbourUp.NOTIFICATION_ID, this::uponNeighbourUp);
        subscribeNotification(NeighbourDown.NOTIFICATION_ID, this::uponNeighbourDown);
        subscribeNotification(ChannelCreated.NOTIFICATION_ID, this::uponChannelCreated);

        /*--------------------- Register Timers -----------------------------------------*/
        registerTimerHandler(InfoTimer.TIMER_ID, this::uponInfoTime);

        registerTimerHandler(PROTOCOL_ID, null);
        // Schedule periodic Pull Gossip messages
        // scheduler.scheduleAtFixedRate(this::sendPullGossip, 0, 10, TimeUnit.SECONDS); // Adjust the interval as needed
    }

    @Override
    public void init(Properties props) {
        //Nothing to do here, we just wait for event from the membership or the application
        int pMetricsInterval = Integer.parseInt(props.getProperty("protocol_metrics_interval", "10000"));
        if (pMetricsInterval > 0)
            setupPeriodicTimer(new InfoTimer(), pMetricsInterval, pMetricsInterval);
    }

    //Upon receiving the channelId from the membership, register our own callbacks and serializers
    private void uponChannelCreated(ChannelCreated notification, short sourceProto) {
        int cId = notification.getChannelId();
        // Allows this protocol to receive events from this channel.
        registerSharedChannel(cId);
        /*---------------------- Register Message Serializers ---------------------- */
        registerMessageSerializer(cId, GossipMessage.MSG_ID, GossipMessage.serializer);
        registerMessageSerializer(cId, PullGossipMessage.MSG_ID, PullGossipMessage.serializer);
        /*---------------------- Register Message Handlers -------------------------- */


        try {
            registerMessageHandler(cId, GossipMessage.MSG_ID, this::uponPBroadcast, this::uponMsgFail);
            registerMessageHandler(cId, PullGossipMessage.MSG_ID, this::uponPBroadcastPull, this::uponMsgFail);
        } catch (HandlerRegistrationException e) {
            logger.error("Error registering message handler: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        //Now we can start sending messages
        channelReady = true;
    }

    private void uponBroadcastRequest(BroadcastRequest request, short sourceProto) {
        if (!channelReady) return;

        delivered.add(request.getMsgId());
        deliveredMsgsMap.put(request.getSender(), delivered);
        msgContentMap.put(request.getMsgId(), request.getMsg());

        // Schedule periodic Pull Gossip messages
        scheduler.scheduleAtFixedRate(this::sendPullGossip, 0, 10, TimeUnit.SECONDS); // Adjust the interval as needed
    }

    private void sendPullGossip() {
        if (!channelReady) return;

        if (!pi.isEmpty()) {
            Host randomNeighbour = randomSelection(t, pi);
            if (randomNeighbour != null) {
                PullGossipMessage msg = new PullGossipMessage(myself, deliveredMsgsMap, msgContentMap);
                sendMessage(msg, randomNeighbour);
                logger.info("Pull Gossip sent {} to {}", msg, randomNeighbour);
            } else
                logger.error("No active neighbor found to send Pull Gossip");
        }
    }

    private void uponPBroadcastPull(PullGossipMessage msg, Host from, short sourceProto, int channelId) {
        if (!channelReady) return;
        if (!myself.equals(from)) {
            logger.info("Received {} from {}", msg, from);
            respondToPullGossip(msg, from);
        }
    }

    private void respondToPullGossip(PullGossipMessage pullGossipMessage, Host requester) {
        if (!channelReady) return;


        byte[] content;
        gossipTarget = requester;
        Map<Host, Set<UUID>> mapgetDelMsg = pullGossipMessage.getDeliveredMsgsMap();
        Set<UUID> requesterDelivered = mapgetDelMsg.get(requester);
        logger.info("requester: {}  delivered messages {}", requester, requesterDelivered);
        logger.info("receiver: {}  delivered messages {}", myself, delivered);

        // Compare the requester's delivered messages with your own delivered messages
        Set<UUID> missingMessages = new HashSet<>(delivered);
        missingMessages.removeAll(requesterDelivered);
        logger.info("missingMessages {}", missingMessages);

        // Send the missing messages to the requester
        for (UUID missingMessageId : missingMessages) {
            content = msgContentMap.get(missingMessageId);
            if (content != null && gossipTarget != null) { // Check if the content and target are not null before creating the GossipMessage
                GossipMessage responseMessage = new GossipMessage(missingMessageId, myself, getProtoId(), content);
                logger.info("Sent {} to {}", responseMessage, gossipTarget);
                sendMessage(responseMessage, gossipTarget);
            }
        }
    }

    /*--------------------------------- Messages ---------------------------------------- */

    private void uponPBroadcast(GossipMessage msg, Host from, short sourceProto, int channelId) {
        logger.info("Received {} from {}", msg, from);

        triggerNotification(new DeliverNotification(msg.getMid(), msg.getSender(), msg.getContent()));

        nMessagesReceived++;
        delivered.add(msg.getMid());
        deliveredMsgsMap.put(myself, delivered);
        msgContentMap.put(msg.getMid(), msg.getContent());
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto,
                             Throwable throwable, int channelId) {
        //If a message fails to be sent, for whatever reason, log the message and the reason
        logger.error("Message {} to {} failed, reason: {}", msg, host, throwable);
    }

    /*--------------------------------- Auxiliary ---------------------------------------- */

    // Method to randomly select gossip target from the set of processes
    private Host randomSelection(int count, Set<Host> hosts) {

        List<Host> hostList = new ArrayList<>(hosts);
        Collections.shuffle(hostList);

        if (!hostList.isEmpty()) return hostList.get(0); // count);
        return null;
    }

    /*--------------------------------- Notifications ---------------------------------------- */

    //When the membership protocol notifies of a new neighbour (or leaving one) simply update my list of neighbours.
    private void uponNeighbourUp(NeighbourUp notification, short sourceProto) {
        for (Host h : notification.getNeighbours()) {
            pi.add(h);
            logger.info("New neighbour: " + h);
        }
    }

    private void uponNeighbourDown(NeighbourDown notification, short sourceProto) {
        for (Host h : notification.getNeighbours()) {
            pi.remove(h);
            logger.info("Neighbour down: " + h);
            // Remove the entries related to the neighbor from delivered messages map
            deliveredMsgsMap.remove(h);
            if (gossipTarget != null && gossipTarget.equals(h)) {
                gossipTarget = null; // Reset the gossip target if it matches the removed neighbor
            }
        }
    }

    //If we passed a value >0 in the METRICS_INTERVAL_KEY property of the channel, this event will be triggered
    //periodically by the channel. This is NOT a protocol timer, but a channel event.
    //Again, we are just showing some of the information you can get from the channel, and use how you see fit.
    //"getInConnections" and "getOutConnections" returns the currently established connection to/from me.
    //"getOldInConnections" and "getOldOutConnections" returns connections that have already been closed.
    private void uponInfoTime(InfoTimer timer, long timerId) {
        StringBuilder sb = new StringBuilder("FloodBroadcast Metrics Metrics:\n");
        sb.append("Number received msgs: ").append(nMessagesReceived).append("\n");
        sb.append("Number of different msgs received: ").append(delivered.size()).append("\n");
        sb.append("Ratio between msgsReceived and diff msgs received: ").
                append((double) nMessagesReceived / (double) delivered.size()).append("\n");

        //getMetrics returns an object with the number of events of each type processed by this protocol.
        //It may or may not be useful to you, but at least you know it exists.
        sb.append(getMetrics());
        logger.info(sb);
    }
}
