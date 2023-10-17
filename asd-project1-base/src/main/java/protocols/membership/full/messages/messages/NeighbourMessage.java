package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;

public class NeighbourMessage extends ProtoMessage {

    public final static short MSG_ID = 105;
    public static ISerializer<NeighbourMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(NeighbourMessage msg, ByteBuf out) throws IOException {
            out.writeChar(msg.getPriority());
            Host.serializer.serialize(msg.newNode, out);
        }

        @Override
        public NeighbourMessage deserialize(ByteBuf in) throws IOException {
            char priority = in.readChar();
            Host h = Host.serializer.deserialize(in);

            return new NeighbourMessage(h, priority);
        }
    };

    private final Host newNode;
    private final char priority;

    public NeighbourMessage(Host newNode, char priority) {
        super(MSG_ID);
        this.newNode = newNode;
        this.priority = priority;
    }

    public Host getNode() {
        return this.newNode;
    }

    public char getPriority() {
        return this.priority;
    }

    @Override
    public String toString() {
        return "NeighbourMessage{" +
                "newNode=" + newNode +
                "priority=" + priority +
                '}';
    }
}
