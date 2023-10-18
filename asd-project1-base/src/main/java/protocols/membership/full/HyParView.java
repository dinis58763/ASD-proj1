package protocols.membership.full;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocols.membership.common.notifications.ChannelCreated;
import protocols.membership.common.notifications.NeighbourDown;
import protocols.membership.common.notifications.NeighbourUp;
import protocols.membership.full.messages.SampleMessage;
import protocols.membership.full.messages.messages.*;
import protocols.membership.full.timers.InfoTimer;
import protocols.membership.full.timers.SampleTimer;
import protocols.membership.full.timers.ShuffleTimer;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.channel.tcp.events.*;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

public class HyParView extends GenericProtocol {

    //Protocol information, to register in babel
    public final static short PROTOCOL_ID = 100;
    public final static String PROTOCOL_NAME = "HyParView";
    private static final Logger logger = LogManager.getLogger(HyParView.class);
    private final Host self;     //My own address/port

    private final Set<Host> membership; //Peers I am connected to


    private final Set<Host> pending; //Peers I am trying to connect to
    private final Set<Host> outgoingConnection;
    private final Set<Host> ingoingConnection;

    //HyParView specific attributes
    private final Set<Host> activeView;
    private final Set<Host> passiveView;
    private final int activeViewLength;
    private final int passiveViewLength;

    private final int activeRandomWalkLength;
    private final int passiveRandomWalkLength;


    private final int sampleTime; //param: timeout for samples
    private final int subsetSize; //param: maximum size of sample;

    private final Random rnd;

    private final int channelId; //Id of the created channel

    public HyParView(Properties props, Host self) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);

        this.self = self;
        this.membership = new HashSet<>();
        this.pending = new HashSet<>();

        this.rnd = new Random();

        this.activeView = new HashSet<>();
        this.passiveView = new HashSet<>();

        this.outgoingConnection = new HashSet<>();
        this.ingoingConnection = new HashSet<>();

        this.activeViewLength = 6;
        this.passiveViewLength = 2;

        this.activeRandomWalkLength = 20;
        this.passiveRandomWalkLength = 10;

        //Get some configurations from the Properties object
        this.subsetSize = Integer.parseInt(props.getProperty("sample_size", "6"));
        this.sampleTime = Integer.parseInt(props.getProperty("sample_time", "2000")); //2 seconds

        String cMetricsInterval = props.getProperty("channel_metrics_interval", "10000"); //10 seconds

        //Create a properties object to setup channel-specific properties. See the channel description for more details.
        Properties channelProps = new Properties();
        channelProps.setProperty(TCPChannel.ADDRESS_KEY, props.getProperty("address")); //The address to bind to
        channelProps.setProperty(TCPChannel.PORT_KEY, props.getProperty("port")); //The port to bind to
        channelProps.setProperty(TCPChannel.METRICS_INTERVAL_KEY, cMetricsInterval); //The interval to receive channel metrics
        channelProps.setProperty(TCPChannel.HEARTBEAT_INTERVAL_KEY, "1000"); //Heartbeats interval for established connections
        channelProps.setProperty(TCPChannel.HEARTBEAT_TOLERANCE_KEY, "3000"); //Time passed without heartbeats until closing a connection
        channelProps.setProperty(TCPChannel.CONNECT_TIMEOUT_KEY, "1000"); //TCP connect timeout
        channelId = createChannel(TCPChannel.NAME, channelProps); //Create the channel with the given properties

        /*---------------------- Register Message Serializers ---------------------- */
        registerMessageSerializer(channelId, SampleMessage.MSG_ID, SampleMessage.serializer);
        registerMessageSerializer(channelId, JoinMessage.MSG_ID, JoinMessage.serializer);
        registerMessageSerializer(channelId, ForwardJoinMessage.MSG_ID, ForwardJoinMessage.serializer);
        registerMessageSerializer(channelId, NeighbourMessage.MSG_ID, NeighbourMessage.serializer);
        registerMessageSerializer(channelId, NeighbourReplyMessage.MSG_ID, NeighbourReplyMessage.serializer);
        registerMessageSerializer(channelId, DisconnectMessage.MSG_ID, DisconnectMessage.serializer);
        registerMessageSerializer(channelId, ShuffleMessage.MSG_ID, ShuffleMessage.serializer);

        /*---------------------- Register Message Handlers -------------------------- */
        registerMessageHandler(channelId, JoinMessage.MSG_ID, this::uponJoin, this::uponMsgFail);
        registerMessageHandler(channelId, ForwardJoinMessage.MSG_ID, this::uponForwardJoin, this::uponMsgFail);
        registerMessageHandler(channelId, NeighbourMessage.MSG_ID, this::uponNeighbourMessage, this::uponMsgFail);
        registerMessageHandler(channelId, NeighbourReplyMessage.MSG_ID, this::uponNeighbourReplyMessage, this::uponMsgFail);
        registerMessageHandler(channelId, DisconnectMessage.MSG_ID, this::uponDisconnectMessage, this::uponMsgFail);


        /*--------------------- Register Timer Handlers ----------------------------- */
        registerTimerHandler(InfoTimer.TIMER_ID, this::uponInfoTime);


        /*-------------------- Register Channel Events ------------------------------- */
        registerChannelEventHandler(channelId, OutConnectionDown.EVENT_ID, this::uponOutConnectionDown);
        registerChannelEventHandler(channelId, OutConnectionFailed.EVENT_ID, this::uponOutConnectionFailed);
        registerChannelEventHandler(channelId, OutConnectionUp.EVENT_ID, this::uponOutConnectionUp);
        registerChannelEventHandler(channelId, InConnectionUp.EVENT_ID, this::uponInConnectionUp);
        registerChannelEventHandler(channelId, InConnectionDown.EVENT_ID, this::uponInConnectionDown);
        registerChannelEventHandler(channelId, ChannelMetrics.EVENT_ID, this::uponChannelMetrics);
    }

    //Gets a random subset from the set of peers
    private static Set<Host> getRandomSubsetExcluding(Set<Host> hostSet, int sampleSize, Host exclude) {
        List<Host> list = new LinkedList<>(hostSet);
        list.remove(exclude);
        Collections.shuffle(list);
        return new HashSet<>(list.subList(0, Math.min(sampleSize, list.size())));
    }

    @Override
    public void init(Properties props) {

        //Inform the dissemination protocol about the channel we created in the constructor
        triggerNotification(new ChannelCreated(channelId));

        //If there is a contact node, attempt to establish connection
        if (props.containsKey("contact")) {
            try {
                String contact = props.getProperty("contact");
                String[] hostElems = contact.split(":");
                Host contactHost = new Host(InetAddress.getByName(hostElems[0]), Short.parseShort(hostElems[1]));

                establishOutgoingConnection(contactHost);

            } catch (Exception e) {
                logger.error("Invalid contact on configuration: '" + props.getProperty("contacts"));
                e.printStackTrace();
                System.exit(-1);
            }
        }

        //Setup the timer used to send samples (we registered its handler on the constructor)
        //setupPeriodicTimer(new SampleTimer(), this.sampleTime, this.sampleTime);

        //Setup the timer to display protocol information (also registered handler previously)
        int pMetricsInterval = Integer.parseInt(props.getProperty("protocol_metrics_interval", "10000"));
        if (pMetricsInterval > 0)
            setupPeriodicTimer(new InfoTimer(), pMetricsInterval, pMetricsInterval);
    }

    /*--------------------------------- Messages ---------------------------------------- */

    private void uponJoin(JoinMessage msg, Host from, short sourceProto, int channelId) {
        //Received a sample from a peer. We add all the unknown peers to the "pending" map and attempt to establish
        //a connection. If the connection is successful, we add the peer to the membership (in the connectionUp callback)
        logger.debug("Received {} from {}", msg, from);

        establishOutgoingConnection(msg.getNode());

        for (Host h : activeView)
            if (!h.equals(msg.getNode())) {
                sendMessage(new ForwardJoinMessage(msg.getNode(), activeRandomWalkLength), h);
                logger.info("Current ActiveView: " + activeView);
                logger.info("Sent ForwardJoin to " + h + " with " + msg.getNode());
            }

        logger.info("Upon Join sent by: " + from);
        logger.info("ActiveView: " + activeView);
        logger.info("PassiveView: " + passiveView);
        logger.info("OutgoingConnection: " + outgoingConnection);
        logger.info("IngoingConnection: " + ingoingConnection);
    }

    private void uponForwardJoin(ForwardJoinMessage msg, Host from, short sourceProto, int channelId) {
        logger.info("Upon ForwardJoin sent by: " + from);
        logger.info("ActiveView: " + activeView);
        logger.info("PassiveView: " + passiveView);
        logger.info("OutgoingConnection: " + outgoingConnection);
        logger.info("IngoingConnection: " + ingoingConnection);

        if (msg.getTimeToLive() == 0 || activeView.size() == 1) {
            this.establishOutgoingConnection(msg.getNode());
        } else {
            if (msg.getTimeToLive() == passiveRandomWalkLength)
                this.addNodePassiveView(msg.getNode());

            Host n;
            do {
                n = this.getRandom(activeView);
            } while (Objects.equals(n, from));

            sendMessage(new ForwardJoinMessage(msg.getNode(), msg.getTimeToLive() - 1), n);
            logger.info("ForwardJoin sent to " + n + " with " + msg.getNode());
        }
    }

    private void uponNeighbourMessage(NeighbourMessage msg, Host from, short sourceProto, int channelId) {
        Host peer = msg.getNode();

        if (msg.getPriority() == 'h' || (msg.getPriority() == 'l' && activeView.size() < activeViewLength)) {
            if (msg.getPriority() == 'h')
                dropRandomElementFromActiveView();
            pending.add(peer);
            openConnection(peer);
            sendMessage(new NeighbourReplyMessage(self, true), peer);
        }
        sendMessage(new NeighbourReplyMessage(self, false), peer);

    }

    private void uponNeighbourReplyMessage(NeighbourReplyMessage msg, Host from, short sourceProto, int channelId) {
        Host peer = msg.getNode();
        if (msg.getAccepted()) {
            passiveView.remove(peer);
            openConnection(peer);
            pending.add(peer);
        } else {
            elevateFromPassiveView();
        }

    }

    private void uponDisconnectMessage(DisconnectMessage msg, Host from, short sourceProto, int channelId) {
        Host peer = msg.getPeer();
        if (activeView.remove(peer))
            addNodePassiveView(peer);

        logger.info("Upon Disconnect sent by: " + from);
        /*logger.info("ActiveView: " + activeView);
        logger.info("PassiveView: " + passiveView);
        logger.info("OutgoingConnection: " + outgoingConnection);
        logger.info("IngoingConnection: " + ingoingConnection);*/
    }


    private void dropRandomElementFromActiveView() {
        Host h = getRandom(activeView);
        closeConnection(h);
        sendMessage(new DisconnectMessage(self), h);
    }

    private void establishOutgoingConnection(Host h) {
        if (!h.equals(self) && !activeView.contains(h)) {
            if (activeView.size() == activeViewLength)
                dropRandomElementFromActiveView();
            pending.add(h);
            openConnection(h);
        }
    }

    private void addNodePassiveView(Host h) {
        if (!h.equals(self) && !activeView.contains(h) && !passiveView.contains(h)) {
            if (passiveView.size() == passiveViewLength) {
                Host n = getRandom(passiveView);
                passiveView.remove(n);
            }
            passiveView.add(h);
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto,
                             Throwable throwable, int channelId) {
        //If a message fails to be sent, for whatever reason, log the message and the reason
        logger.error("Message {} to {} failed, reason: {}", msg, host, throwable);
    }

    //Gets a random element from the set of peers
    private Host getRandom(Set<Host> hostSet) {
        int idx = rnd.nextInt(hostSet.size());
        int i = 0;
        for (Host h : hostSet) {
            if (i == idx)
                return h;
            i++;
        }
        return null;
    }

    private void elevateFromPassiveView() {
        Host peerToBeElevated = getRandom(passiveView);
        openConnection(peerToBeElevated);
        pending.add(peerToBeElevated);
    }

    /* --------------------------------- TCPChannel Events ---------------------------- */

    //If a connection is successfully established, this event is triggered. In this protocol, we want to add the
    //respective peer to the membership, and inform the Dissemination protocol via a notification.
    private void uponOutConnectionUp(OutConnectionUp event, int channelId) {
        Host peer = event.getNode();
        logger.debug("Connection to {} is up", peer);

        pending.remove(peer);
        if (outgoingConnection.add(peer)) {
            logger.info("Outgoing connection established with: " + event.getNode());
        }

        if (ingoingConnection.contains(peer)) {
            activeView.add(peer);
            triggerNotification(new NeighbourUp(peer));
            logger.info("Established bidirectional connection with: " + peer);
            logger.info("ACTIVEVIEW: " + activeView);
            logger.info("PASSIVEVIEW: " + passiveView);
        }

        if (outgoingConnection.size() == 1 && ingoingConnection.size() == 0) {
            sendMessage(new JoinMessage(self), event.getNode());
            logger.info("Sent JoinMessage to " + event.getNode());
        }

        if (passiveView.contains(peer)) {
            char priority = activeView.size() == 0 ? 'h' : 'l';
            sendMessage(new NeighbourMessage(self, priority), peer);
        }
    }

    //If an established connection is disconnected, remove the peer from the active view and add it
    //to the passive view. Additionally, try to establish a connection with a node from the passive view.
    //Alternatively, we could do smarter things like retrying the connection X times.
    private void uponOutConnectionDown(OutConnectionDown event, int channelId) {
        Host peer = event.getNode();
        logger.debug("Connection to {} is down cause {}", peer, event.getCause());

        outgoingConnection.remove(peer);

        if (activeView.remove(peer)) {
            passiveView.add(peer);
            triggerNotification(new NeighbourDown(event.getNode()));
            elevateFromPassiveView();
        }
    }

    //If a connection fails to be established, the node is removed from the pending peer.
    //If this connection was the case of a node being elevated from the passive view, the
    //operation is retried with another random node
    //Thus the peer will be in the pending set, and not in the membership (unless something is very wrong with our code)
    private void uponOutConnectionFailed(OutConnectionFailed<ProtoMessage> event, int channelId) {
        Host peer = event.getNode();
        logger.debug("Connection to {} failed cause: {}", peer, peer);

        pending.remove(peer);

        if (passiveView.remove(peer) && passiveView.size() > 0)
            elevateFromPassiveView();
    }

    //If someone established a connection to me, this event is triggered. In this protocol we do nothing with this event.
    //If we want to add the peer to the membership, we will establish our own outgoing connection.
    // (not the smartest protocol, but its simple)
    private void uponInConnectionUp(InConnectionUp event, int channelId) {
        logger.trace("Connection from {} is up", event.getNode());

        if (ingoingConnection.add(event.getNode())) {
            logger.info("Ingoing connection established with: " + event.getNode());
        }

        if (outgoingConnection.contains(event.getNode())) {
            activeView.add(event.getNode());
            triggerNotification(new NeighbourUp(event.getNode()));
            logger.info("Established bidirectional connection with: " + event.getNode());
            logger.info("ACTIVEVIEW: " + activeView);
            logger.info("PASSIVEVIEW: " + passiveView);
        }

        if (ingoingConnection.size() > 1) {
            establishOutgoingConnection(event.getNode());
        }
    }

    //A connection someone established to me is disconnected.
    private void uponInConnectionDown(InConnectionDown event, int channelId) {
        logger.trace("Connection from {} is down, cause: {}", event.getNode(), event.getCause());

        ingoingConnection.remove(event.getNode());
        activeView.remove(event.getNode());
    }

    /* --------------------------------- Metrics ---------------------------- */

    //If we setup the InfoTimer in the constructor, this event will be triggered periodically.
    //We are simply printing some information to present during runtime.
    private void uponInfoTime(InfoTimer timer, long timerId) {
        StringBuilder sb = new StringBuilder("Membership Metrics:\n");
        sb.append("Membership: ").append(membership).append("\n");
        sb.append("PendingMembership: ").append(pending).append("\n");
        //getMetrics returns an object with the number of events of each type processed by this protocol.
        //It may or may not be useful to you, but at least you know it exists.
        sb.append(getMetrics());
        logger.info(sb);
    }

    //If we passed a value >0 in the METRICS_INTERVAL_KEY property of the channel, this event will be triggered
    //periodically by the channel. This is NOT a protocol timer, but a channel event.
    //Again, we are just showing some of the information you can get from the channel, and use how you see fit.
    //"getInConnections" and "getOutConnections" returns the currently established connection to/from me.
    //"getOldInConnections" and "getOldOutConnections" returns connections that have already been closed.
    private void uponChannelMetrics(ChannelMetrics event, int channelId) {
        StringBuilder sb = new StringBuilder("Channel Metrics:\n");
        sb.append("In channels:\n");
        event.getInConnections().forEach(c -> sb.append(String.format("\t%s: msgOut=%s (%s) msgIn=%s (%s)\n",
                c.getPeer(), c.getSentAppMessages(), c.getSentAppBytes(), c.getReceivedAppMessages(),
                c.getReceivedAppBytes())));
        event.getOldInConnections().forEach(c -> sb.append(String.format("\t%s: msgOut=%s (%s) msgIn=%s (%s) (old)\n",
                c.getPeer(), c.getSentAppMessages(), c.getSentAppBytes(), c.getReceivedAppMessages(),
                c.getReceivedAppBytes())));
        sb.append("Out channels:\n");
        event.getOutConnections().forEach(c -> sb.append(String.format("\t%s: msgOut=%s (%s) msgIn=%s (%s)\n",
                c.getPeer(), c.getSentAppMessages(), c.getSentAppBytes(), c.getReceivedAppMessages(),
                c.getReceivedAppBytes())));
        event.getOldOutConnections().forEach(c -> sb.append(String.format("\t%s: msgOut=%s (%s) msgIn=%s (%s) (old)\n",
                c.getPeer(), c.getSentAppMessages(), c.getSentAppBytes(), c.getReceivedAppMessages(),
                c.getReceivedAppBytes())));
        sb.setLength(sb.length() - 1);
        logger.info(sb);
    }
}