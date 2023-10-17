package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;

public class NeighbourReplyMessage extends ProtoMessage {
    public final static short MSG_ID = 106;
    public static ISerializer<NeighbourReplyMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(NeighbourReplyMessage msg, ByteBuf out) throws IOException {
            out.writeBoolean(msg.getAccepted());
            Host.serializer.serialize(msg.getNode(), out);
        }

        @Override
        public NeighbourReplyMessage deserialize(ByteBuf in) throws IOException {
            boolean accepted = in.readBoolean();
            Host h = Host.serializer.deserialize(in);

            return new NeighbourReplyMessage(h, accepted);
        }
    };

    private final Host node;
    private final boolean accepted;

    public NeighbourReplyMessage(Host node, boolean accepted) {
        super(MSG_ID);
        this.node = node;
        this.accepted = accepted;
    }

    public Host getNode() {
        return this.node;
    }

    public boolean getAccepted() {
        return this.accepted;
    }

    @Override
    public String toString() {
        return "NeighbourReplyMessage{" +
                "newNode=" + node +
                "accepted=" + accepted +
                '}';
    }
}
