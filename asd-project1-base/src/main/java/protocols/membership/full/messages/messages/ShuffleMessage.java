package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class ShuffleMessage extends ProtoMessage {

    public final static short MSG_ID = 107;
    public static ISerializer<ShuffleMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(ShuffleMessage msg, ByteBuf out) throws IOException {
            Host.serializer.serialize(msg.node, out);

            out.writeInt(msg.activeView.size());
            for (Host h : msg.activeView)
                Host.serializer.serialize(h, out);

            out.writeInt(msg.passiveView.size());
            for (Host h : msg.passiveView)
                Host.serializer.serialize(h, out);

            out.writeInt(msg.timeToLive);
        }

        @Override
        public ShuffleMessage deserialize(ByteBuf in) throws IOException {
            Host node = Host.serializer.deserialize(in);

            int size = in.readInt();
            Set<Host> activeView = new HashSet<>(size, 1);
            for (int i = 0; i < size; i++)
                activeView.add(Host.serializer.deserialize(in));

            size = in.readInt();
            Set<Host> passiveView = new HashSet<>(size, 1);
            for (int i = 0; i < size; i++)
                passiveView.add(Host.serializer.deserialize(in));

            int timeToLive = in.readInt();

            return new ShuffleMessage(node, activeView, passiveView, timeToLive);
        }
    };

    private final Host node;
    private final Set<Host> activeView;
    private final Set<Host> passiveView;
    private final int timeToLive;

    public ShuffleMessage(Host node, Set<Host> activeView, Set<Host> passiveView, int timeToLive) {
        super(MSG_ID);
        this.node = node;
        this.activeView = activeView;
        this.passiveView = passiveView;
        this.timeToLive = timeToLive;
    }

    public Host getNode() {
        return node;
    }

    public Set<Host> getActiveView() {
        return activeView;
    }

    public Set<Host> getPassiveView() {
        return passiveView;
    }

    public int getTimeToLive() {
        return timeToLive;
    }

    @Override
    public String toString() {
        return "ShuffleMessage{" +
                "node=" + node +
                "activeView=" + activeView +
                "passiveView=" + passiveView +
                '}';
    }
}
