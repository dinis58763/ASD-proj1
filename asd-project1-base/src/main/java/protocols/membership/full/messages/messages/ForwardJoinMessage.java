package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;

public class ForwardJoinMessage extends ProtoMessage {

    public final static short MSG_ID = 103;
    public static ISerializer<ForwardJoinMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(ForwardJoinMessage msg, ByteBuf out) throws IOException {
            out.writeInt(msg.getTimeToLive());
            Host.serializer.serialize(msg.newNode, out);
        }

        @Override
        public ForwardJoinMessage deserialize(ByteBuf in) throws IOException {
            int timeToLive = in.readInt();
            Host h = Host.serializer.deserialize(in);

            return new ForwardJoinMessage(h, timeToLive);
        }
    };

    private final Host newNode;
    private final int timeToLive;

    public ForwardJoinMessage(Host newNode, int timeToLive) {
        super(MSG_ID);
        this.newNode = newNode;
        this.timeToLive = timeToLive;
    }

    public Host getNode() {
        return this.newNode;
    }

    public int getTimeToLive() {
        return this.timeToLive;
    }

    @Override
    public String toString() {
        return "ForwardJoinMessage{" +
                "newNode=" + newNode +
                "timeToLive=" + timeToLive +
                '}';
    }
}
