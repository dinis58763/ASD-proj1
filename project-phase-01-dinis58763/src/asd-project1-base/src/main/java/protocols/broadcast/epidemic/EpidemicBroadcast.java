package protocols.broadcast.epidemic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocols.broadcast.common.BroadcastRequest;
import protocols.broadcast.common.DeliverNotification;
import protocols.broadcast.flood.messages.FloodMessage;
import protocols.membership.common.notifications.ChannelCreated;
import protocols.membership.common.notifications.NeighbourDown;
import protocols.membership.common.notifications.NeighbourUp;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;
import java.util.*;

public class EpidemicBroadcast extends GenericProtocol {
    //Protocol information, to register in babel
    public static final String PROTOCOL_NAME = "Epidemic (Gossip)";
    public static final short PROTOCOL_ID = 201;
    private static final Logger logger = LogManager.getLogger(EpidemicBroadcast.class);
    private final Host myself; //My own address/port
    private final Set<Host> neighbours; //My known neighbours (a.k.a peers the membership protocol told me about)
    private final Set<UUID> delivered;

    private int t; //fanout of the protocol -  t = ln(pi)

    //We can only start sending messages after the membership protocol informed us that the channel is ready
    private boolean channelReady;

    public EpidemicBroadcast(Properties properties, Host myself) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        this.myself = myself;
        neighbours = new HashSet<>();
        channelReady = false;

        t = 5;
        delivered = new HashSet<>();

        /*--------------------- Register Request Handlers ----------------------------- */
        registerRequestHandler(BroadcastRequest.REQUEST_ID, this::uponBroadcastRequest);

        /*--------------------- Register Notification Handlers ----------------------------- */
        subscribeNotification(NeighbourUp.NOTIFICATION_ID, this::uponNeighbourUp);
        subscribeNotification(NeighbourDown.NOTIFICATION_ID, this::uponNeighbourDown);
        subscribeNotification(ChannelCreated.NOTIFICATION_ID, this::uponChannelCreated);
    }

    @Override
    public void init(Properties props) {
        //Nothing to do here, we just wait for event from the membership or the application
    }

    private Set<Host> randomSelection(int fanout, Set<Host> neighbours) {
        Set<Host> chosenNeighbours = new HashSet<>();
        List<Host> neighboursList = new ArrayList<>(neighbours);

        int numberOfChosen = Math.min(fanout, neighbours.size());

        for (int i = 0; i < numberOfChosen; i++) {
            int randomIndex = (int) Math.ceil((Math.random() * (double) (neighboursList.size() - 1)));
            chosenNeighbours.add(neighboursList.remove(randomIndex));
        }

        return chosenNeighbours;
    }

    //Upon receiving the channelId from the membership, register our own callbacks and serializers
    private void uponChannelCreated(ChannelCreated notification, short sourceProto) {
        int cId = notification.getChannelId();
        // Allows this protocol to receive events from this channel.
        registerSharedChannel(cId);
        /*---------------------- Register Message Serializers ---------------------- */
        registerMessageSerializer(cId, FloodMessage.MSG_ID, FloodMessage.serializer);

        /*---------------------- Register Message Handlers -------------------------- */
        try {
            registerMessageHandler(cId, FloodMessage.MSG_ID, this::uponGossipMessage, this::uponMsgFail);
        } catch (HandlerRegistrationException e) {
            logger.error("Error registering message handler: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        //Now we can start sending messages
        channelReady = true;
    }

    /*--------------------------------- Requests ---------------------------------------- */
    private void uponBroadcastRequest(BroadcastRequest request, short sourceProto) {
        if (!channelReady) return;

        //Create the message object.
        FloodMessage msg = new FloodMessage(request.getMsgId(), request.getSender(), sourceProto, request.getMsg());

        //Deliver the message to the application (even if it came from it)
        triggerNotification(new DeliverNotification(msg.getMid(), msg.getSender(), msg.getContent()));
        delivered.add(msg.getMid());

        Set<Host> gossipTargets = this.randomSelection(t, neighbours);

        gossipTargets.forEach(host -> {
            logger.trace("Sent {} to {}", msg, host);
            sendMessage(msg, host);
        });
        logger.info("Current neighbours: " + neighbours);
        logger.info("1Gossip Targets: " + gossipTargets);
    }

    /*--------------------------------- Messages ---------------------------------------- */
    private void uponGossipMessage(FloodMessage msg, Host from, short sourceProto, int channelId) {
        logger.trace("Received {} from {}", msg, from);

        //If we already delivered it once, do nothing (or we would end up with a nasty infinite loop)
        if (delivered.add(msg.getMid())) {

            //Deliver the message to the application (even if it came from it)
            triggerNotification(new DeliverNotification(msg.getMid(), msg.getSender(), msg.getContent()));

            //Choose t neighbours (not counting the sender) and sends them the message
            Set<Host> copyNeighbours = new HashSet<>(neighbours);
            copyNeighbours.remove(from);

            Set<Host> gossipTargets = this.randomSelection(t, copyNeighbours);

            gossipTargets.forEach(host -> {
                logger.trace("Sent {} to {}", msg, host);
                sendMessage(msg, host);
            });
            logger.info("Current neighbours: " + neighbours);
            logger.info("Gossip Targets: " + gossipTargets);
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto,
                             Throwable throwable, int channelId) {
        //If a message fails to be sent, for whatever reason, log the message and the reason
        logger.error("Message {} to {} failed, reason: {}", msg, host, throwable);
    }

    /*--------------------------------- Notifications ---------------------------------------- */

    //When the membership protocol notifies of a new neighbour (or leaving one) simply update my list of neighbours.
    private void uponNeighbourUp(NeighbourUp notification, short sourceProto) {
        for (Host h : notification.getNeighbours()) {
            neighbours.add(h);
            calculateFanout();
            logger.info("New neighbour: " + h);
            logger.info("Number of Neighbours: " + neighbours.size() + " fanout: " + t);
        }
    }

    private void uponNeighbourDown(NeighbourDown notification, short sourceProto) {
        for (Host h : notification.getNeighbours()) {
            neighbours.remove(h);
            calculateFanout();
            logger.info("Neighbour down: " + h);
            logger.info("Number of Neighbours: " + neighbours.size() + " fanout: " + t);
        }
    }

    private void calculateFanout() {
        t = (int) Math.max(5, Math.ceil(Math.log(neighbours.size())));
    }
}
