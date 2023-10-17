package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;

public class JoinMessage extends ProtoMessage {

    public final static short MSG_ID = 102;

    public static ISerializer<JoinMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(JoinMessage msg, ByteBuf out) throws IOException {
            Host.serializer.serialize(msg.newNode, out);
        }

        @Override
        public JoinMessage deserialize(ByteBuf in) throws IOException {
            return new JoinMessage(Host.serializer.deserialize(in));
        }
    };

    private final Host newNode;

    public JoinMessage(Host newNode) {
        super(MSG_ID);
        this.newNode = newNode;
    }

    public Host getNode() {
        return this.newNode;
    }

    @Override
    public String toString() {
        return "JoinMessage{" +
                "node=" + newNode +
                '}';
    }
}
