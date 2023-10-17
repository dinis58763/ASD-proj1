package protocols.broadcast.reliable.messages;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;
import pt.unl.fct.di.novasys.network.data.Host;

public class PullGossipMessage extends ProtoMessage {

    // GossipMessage msg = new GossipMessage(request.getMsgId(), request.getSender(), sourceProto, request.getMsg());
    public static final short MSG_ID = 202; // 201;

    private Host requester;
    private Map<Host, Set<UUID>> deliveredMsgsMap = new HashMap<>();
    private Map<UUID, byte[]> msgContentMap = new HashMap<>();

    public PullGossipMessage(Host requester, Map<Host, Set<UUID>> deliveredMsgsMap, Map<UUID, byte[]> msgContentMap) {
        super(MSG_ID);
        this.requester = requester;
        this.deliveredMsgsMap = deliveredMsgsMap;
        this.msgContentMap = msgContentMap;
    }

    public Host getRequester() {
        return requester;
    }

    public Map<Host, Set<UUID>> getDeliveredMsgsMap() {
        return deliveredMsgsMap;
    }

    public Set<UUID> getDeliveredMessages(Host host) {
        return deliveredMsgsMap.get(host);
    }

    public Map<UUID, byte[]> getContentMsgMap() {
        return msgContentMap;
    }

    public byte[] getMessageContent(UUID mid) {
        return msgContentMap.get(mid);
    }

    public static ISerializer<PullGossipMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(PullGossipMessage message, ByteBuf out) throws IOException {
            Host.serializer.serialize(message.requester, out);
            // Serialize the size of the map
            out.writeInt(message.deliveredMsgsMap.size());

            // Serialize the map entries (Host -> Set<UUID>)
            for (Map.Entry<Host, Set<UUID>> entry : message.deliveredMsgsMap.entrySet()) {
                Host.serializer.serialize(entry.getKey(), out);

                // Serialize the size of the UUID set
                out.writeInt(entry.getValue().size());

                // Serialize UUIDs within the set
                for (UUID uuid : entry.getValue()) {
                    out.writeLong(uuid.getMostSignificantBits());
                    out.writeLong(uuid.getLeastSignificantBits());
                }
            }

            // Serialize the size of the content map
            out.writeInt(message.msgContentMap.size());

            // Serialize the content map entries (UUID -> byte[])
            for (Map.Entry<UUID, byte[]> entry : message.msgContentMap.entrySet()) {
                // Serialize UUID
                out.writeLong(entry.getKey().getMostSignificantBits());
                out.writeLong(entry.getKey().getLeastSignificantBits());

                // Serialize content
                byte[] content = entry.getValue();
                out.writeInt(content.length);
                out.writeBytes(content);
            }
        }

        @Override
        public PullGossipMessage deserialize(ByteBuf in) throws IOException {
            Host requester = Host.serializer.deserialize(in);

            // Deserialize the map size
            int mapSize = in.readInt();
            Map<Host, Set<UUID>> deliveredMsgsMap = new HashMap<>();

            // Deserialize map entries
            for (int i = 0; i < mapSize; i++) {
                Host host = Host.serializer.deserialize(in);

                // Deserialize the size of the UUID set
                int setSize = in.readInt();

                // Deserialize UUIDs
                Set<UUID> uuidSet = new HashSet<>();
                for (int j = 0; j < setSize; j++) {
                    long mostSigBits = in.readLong();
                    long leastSigBits = in.readLong();
                    uuidSet.add(new UUID(mostSigBits, leastSigBits));
                }
                deliveredMsgsMap.put(host, uuidSet);
            }

            // Deserialize the content map size
            int msgContentMapSize = in.readInt();
            Map<UUID, byte[]> msgContentMap = new HashMap<>();

            // Deserialize content map entries
            for (int k = 0; k < msgContentMapSize; k++) {
                long contentUuidMostSigBits = in.readLong();
                long contentUuidLeastSigBits = in.readLong();
                UUID contentUuid = new UUID(contentUuidMostSigBits, contentUuidLeastSigBits);

                int contentSize = in.readInt();
                byte[] content = new byte[contentSize];
                in.readBytes(content);

                msgContentMap.put(contentUuid, content);
            }
            return new PullGossipMessage(requester, deliveredMsgsMap, msgContentMap);
        }
    };
}