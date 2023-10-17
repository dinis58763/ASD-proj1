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
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.data.Host;

public class ReliableBroadcast extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(ReliableBroadcast.class);

    //Protocol information, to register in babel
    public static final String PROTOCOL_NAME = "Reliable";
    public static final short PROTOCOL_ID = 200;

    private final Host myself; //My own address/port
    private int t; // number of recipients or neighbors to which a message is sent or forwarded by a process
    private int threshold; // size of the msg which will affect switch between eager push and pull gossip
    private final Set<Host> pi; // set of processes you send a message to
    private final Set<UUID> delivered; // //Ids of messages already delivered
    //We can only start sending messages after the membership protocol informed us that the channel is ready
    private boolean channelReady;
    private Host gossipTarget;

    // Create a ScheduledExecutorService
    ScheduledExecutorService scheduler;

    // Map to keep track of delivered messages by neighbour
    private final Map<Host, Set<UUID>> deliveredMsgsMap;

    // Map to keep track of the messages' content according by mid
    private final Map<UUID, byte[]> msgContentMap;

    public ReliableBroadcast(Properties properties, Host myself) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.myself = myself;
        t = 1;
        pi = new HashSet<>();
        delivered = new HashSet<>();
        channelReady = false;
        threshold = 1024;
        deliveredMsgsMap = new HashMap<>();
        msgContentMap = new HashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);

        /*--------------------- Register Request Handlers -----------------------------*/
        registerRequestHandler(BroadcastRequest.REQUEST_ID, this::uponBroadcastRequest);

        /*--------------------- Register Notification Handlers ----------------------------- */
        subscribeNotification(NeighbourUp.NOTIFICATION_ID, this::uponNeighbourUp);
        subscribeNotification(NeighbourDown.NOTIFICATION_ID, this::uponNeighbourDown);
        subscribeNotification(ChannelCreated.NOTIFICATION_ID, this::uponChannelCreated);

        // Schedule periodic Pull Gossip messages
        // scheduler.scheduleAtFixedRate(this::sendPullGossip, 0, 10, TimeUnit.SECONDS); // Adjust the interval as needed
    }

    @Override
    public void init(Properties props) {
        //Nothing to do here, we just wait for event from the membership or the application
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

        logger.info("request.getSender() {}", request.getSender());
        logger.info("msgContentMap {}", msgContentMap);
        logger.info("deliveredMsgsMap {}", deliveredMsgsMap);
        
        // Schedule periodic Pull Gossip messages
        scheduler.scheduleAtFixedRate(this::sendPullGossip, 0, 10, TimeUnit.SECONDS); // Adjust the interval as needed
    }

    private void sendPullGossip() {
        if (!channelReady) return;

        if (!pi.isEmpty()) {
            Host randomNeighbour = randomSelection(t, pi);
            if(randomNeighbour != null) {
                PullGossipMessage msg = new PullGossipMessage(myself, deliveredMsgsMap, msgContentMap);
                sendMessage(msg, randomNeighbour);
                logger.info("Pull Gossip sent {} to {}", msg, randomNeighbour);
            }
            else 
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
        // Map<UUID, byte[]> mapgetContMsg = pullGossipMessage.getContentMsgMap();
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
            logger.info("missingMessageId {}  content {}", missingMessageId, content);
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

        if(!hostList.isEmpty()) return hostList.get(0); // count);
        return null;
    }

    /*--------------------------------- Notifications ---------------------------------------- */

    //When the membership protocol notifies of a new neighbour (or leaving one) simply update my list of neighbours.
    private void uponNeighbourUp(NeighbourUp notification, short sourceProto) {
        for(Host h: notification.getNeighbours()) {
            pi.add(h);
            logger.info("New neighbour: " + h);
        }
    }

    private void uponNeighbourDown(NeighbourDown notification, short sourceProto) {
        for(Host h: notification.getNeighbours()) {
            pi.remove(h);
            logger.info("Neighbour down: " + h);
            // Remove the entries related to the neighbor from delivered messages map
            deliveredMsgsMap.remove(h);
            if (gossipTarget != null && gossipTarget.equals(h)) {
                gossipTarget = null; // Reset the gossip target if it matches the removed neighbor
            }
	    }
    }
}
