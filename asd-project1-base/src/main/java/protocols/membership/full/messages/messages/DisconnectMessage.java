package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;

public class DisconnectMessage extends ProtoMessage {

    public final static short MSG_ID = 104;
    public static ISerializer<DisconnectMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(DisconnectMessage msg, ByteBuf out) throws IOException {
            Host.serializer.serialize(msg.peer, out);
        }

        @Override
        public DisconnectMessage deserialize(ByteBuf in) throws IOException {
            return new DisconnectMessage(Host.serializer.deserialize(in));
        }
    };
    private final Host peer;

    public DisconnectMessage(Host peer) {
        super(MSG_ID);
        this.peer = peer;
    }

    public Host getPeer() {
        return this.peer;
    }

    @Override
    public String toString() {
        return "DisconnectMessage{" +
                "peer=" + peer +
                '}';
    }
}
