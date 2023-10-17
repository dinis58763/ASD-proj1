package protocols.membership.full.messages.messages;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class SampleMessage extends ProtoMessage {

    public final static short MSG_ID = 101;
    public static ISerializer<protocols.membership.full.messages.SampleMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(protocols.membership.full.messages.SampleMessage sampleMessage, ByteBuf out) throws IOException {
            out.writeInt(sampleMessage.getSample().size());
            for (Host h : sampleMessage.getSample())
                Host.serializer.serialize(h, out);
        }

        @Override
        public protocols.membership.full.messages.SampleMessage deserialize(ByteBuf in) throws IOException {
            int size = in.readInt();
            Set<Host> subset = new HashSet<>(size, 1);
            for (int i = 0; i < size; i++)
                subset.add(Host.serializer.deserialize(in));
            return new protocols.membership.full.messages.SampleMessage(subset);
        }
    };
    private final Set<Host> sample;

    public SampleMessage(Set<Host> sample) {
        super(MSG_ID);
        this.sample = sample;
    }

    public Set<Host> getSample() {
        return sample;
    }

    @Override
    public String toString() {
        return "SampleMessage{" +
                "subset=" + sample +
                '}';
    }
}
