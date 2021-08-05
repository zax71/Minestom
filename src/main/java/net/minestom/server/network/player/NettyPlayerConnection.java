package net.minestom.server.network.player;

import net.kyori.adventure.translation.GlobalTranslator;
import net.minestom.server.MinecraftServer;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.PacketProcessor;
import net.minestom.server.network.packet.FramedPacket;
import net.minestom.server.network.packet.server.ComponentHoldingServerPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.socket.Server;
import net.minestom.server.network.socket.Worker;
import net.minestom.server.utils.PacketUtils;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;

/**
 * Represents a networking connection with Netty.
 * <p>
 * It is the implementation used for all network client.
 */
public class NettyPlayerConnection extends PlayerConnection {
    private final Worker worker;
    private final SocketChannel channel;
    private SocketAddress remoteAddress;

    private volatile boolean encrypted = false;
    private volatile boolean compressed = false;

    //Could be null. Only used for Mojang Auth
    private byte[] nonce = new byte[4];

    // Data from client packets
    private String loginUsername;
    private String serverAddress;
    private int serverPort;
    private int protocolVersion;

    // Used for the login plugin request packet, to retrieve the channel from a message id,
    // cleared once the player enters the play state
    private final Map<Integer, String> pluginRequestMap = new ConcurrentHashMap<>();

    // Bungee
    private UUID bungeeUuid;
    private PlayerSkin bungeeSkin;

    private final ByteBuffer tickBuffer = ByteBuffer.allocateDirect(Server.SOCKET_BUFFER_SIZE);
    private volatile ByteBuffer cacheBuffer;

    public NettyPlayerConnection(@NotNull Worker worker, @NotNull SocketChannel channel, SocketAddress remoteAddress) {
        super();
        this.worker = worker;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
    }

    public void processPackets(Worker.Context workerContext, PacketProcessor packetProcessor) {
        final var readBuffer = workerContext.readBuffer;
        final int limit = readBuffer.limit();
        // Read all packets
        while (readBuffer.remaining() > 0) {
            readBuffer.mark(); // Mark the beginning of the packet
            try {
                // Read packet
                final int packetLength = Utils.readVarInt(readBuffer);
                final int packetEnd = readBuffer.position() + packetLength;
                if (packetEnd > readBuffer.limit()) {
                    // Integrity fail
                    throw new BufferUnderflowException();
                }

                readBuffer.limit(packetEnd); // Ensure that the reader doesn't exceed packet bound

                // Read protocol
                ByteBuffer content;
                if (!compressed) {
                    // Compression disabled, payload is following
                    content = readBuffer;
                } else {
                    final int dataLength = Utils.readVarInt(readBuffer);
                    if (dataLength == 0) {
                        // Data is too small to be compressed, payload is following
                        content = readBuffer;
                    } else {
                        // Decompress to content buffer
                        content = workerContext.contentBuffer.clear();
                        try {
                            final var inflater = workerContext.inflater;
                            inflater.setInput(readBuffer);
                            inflater.inflate(content);
                            inflater.reset();
                        } catch (DataFormatException e) {
                            e.printStackTrace();
                        }
                        content.flip();
                    }
                }

                // Process packet
                final int packetId = Utils.readVarInt(content);
                try {
                    packetProcessor.process(this, packetId, content);
                } catch (Exception e) {
                    // Error while reading the packet
                    MinecraftServer.getExceptionManager().handleException(e);
                    break;
                }

                // Return to original state (before writing)
                readBuffer.limit(limit).position(packetEnd);
            } catch (BufferUnderflowException e) {
                readBuffer.reset();
                this.cacheBuffer = ByteBuffer.allocateDirect(readBuffer.remaining())
                        .put(readBuffer).flip();
                break;
            }
        }
    }

    public void consumeCache(ByteBuffer buffer) {
        if (cacheBuffer == null) {
            return;
        }
        buffer.put(cacheBuffer);
        this.cacheBuffer = null;
    }

    /**
     * Sets the encryption key and add the codecs to the pipeline.
     *
     * @param secretKey the secret key to use in the encryption
     * @throws IllegalStateException if encryption is already enabled for this connection
     */
    public void setEncryptionKey(@NotNull SecretKey secretKey) {
        Check.stateCondition(encrypted, "Encryption is already enabled!");
        this.encrypted = true;
        // TODO
    }

    /**
     * Enables compression and add a new codec to the pipeline.
     *
     * @throws IllegalStateException if encryption is already enabled for this connection
     */
    public void startCompression() {
        Check.stateCondition(compressed, "Compression is already enabled!");
        final int threshold = MinecraftServer.getCompressionThreshold();
        Check.stateCondition(threshold == 0, "Compression cannot be enabled because the threshold is equal to 0");
        writeAndFlush(new SetCompressionPacket(threshold));
        this.compressed = true;
    }

    /**
     * Writes a packet to the connection channel.
     * <p>
     * All packets are flushed during {@link net.minestom.server.entity.Player#update(long)}.
     *
     * @param serverPacket the packet to write
     */
    @Override
    public void sendPacket(@NotNull ServerPacket serverPacket, boolean skipTranslating) {
        if (!channel.isConnected()) return;
        if (shouldSendPacket(serverPacket)) {
            final Player player = getPlayer();
            if (player != null) {
                // Flush happen during #update()
                if ((MinestomAdventure.AUTOMATIC_COMPONENT_TRANSLATION && !skipTranslating) && serverPacket instanceof ComponentHoldingServerPacket) {
                    serverPacket = ((ComponentHoldingServerPacket) serverPacket).copyWithOperator(component ->
                            GlobalTranslator.render(component, Objects.requireNonNullElseGet(player.getLocale(), MinestomAdventure::getDefaultLocale)));
                }
                write(serverPacket);
            } else {
                // Player is probably not logged yet
                writeAndFlush(serverPacket);
            }
        }
    }

    public void write(@NotNull ByteBuffer buffer) {
        synchronized (tickBuffer) {
            buffer.flip();
            if (buffer.limit() > tickBuffer.remaining()) {
                // Tick buffer is full, flush before appending
                flush();
            }
            this.tickBuffer.put(buffer);
        }
    }

    public void write(@NotNull FramedPacket framedPacket) {
        write(framedPacket.body());
    }

    public void write(@NotNull ServerPacket packet) {
        // TODO write directly to the tick buffer
        write(PacketUtils.createFramedPacket(packet, compressed));
    }

    public void writeAndFlush(@NotNull ServerPacket packet) {
        synchronized (tickBuffer) {
            write(packet);
            flush();
        }
    }

    public void flush() {
        if (!channel.isOpen()) return;
        synchronized (tickBuffer) {
            if (tickBuffer.position() == 0) return;
            try {
                this.channel.write(tickBuffer.flip());
            } catch (IOException e) {
                MinecraftServer.getExceptionManager().handleException(e);
            } finally {
                this.tickBuffer.clear();
            }
        }
    }

    @Override
    public @NotNull SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Changes the internal remote address field.
     * <p>
     * Mostly unsafe, used internally when interacting with a proxy.
     *
     * @param remoteAddress the new connection remote address
     */
    @ApiStatus.Internal
    public void setRemoteAddress(@NotNull SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }


    @Override
    public void disconnect() {
        try {
            this.worker.disconnect(this, channel);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public @NotNull SocketChannel getChannel() {
        return channel;
    }

    /**
     * Retrieves the username received from the client during connection.
     * <p>
     * This value has not been checked and could be anything.
     *
     * @return the username given by the client, unchecked
     */
    public @Nullable String getLoginUsername() {
        return loginUsername;
    }

    /**
     * Sets the internal login username field.
     *
     * @param loginUsername the new login username field
     */
    public void UNSAFE_setLoginUsername(@NotNull String loginUsername) {
        this.loginUsername = loginUsername;
    }

    /**
     * Gets the server address that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server address used
     */
    @Override
    public @Nullable String getServerAddress() {
        return serverAddress;
    }

    /**
     * Gets the server port that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server port used
     */
    @Override
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Gets the protocol version of a client.
     *
     * @return protocol version of client.
     */
    @Override
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Used in {@link net.minestom.server.network.packet.client.handshake.HandshakePacket} to change the internal fields.
     *
     * @param serverAddress   the server address which the client used
     * @param serverPort      the server port which the client used
     * @param protocolVersion the protocol version which the client used
     */
    public void refreshServerInformation(@Nullable String serverAddress, int serverPort, int protocolVersion) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.protocolVersion = protocolVersion;
    }

    public @Nullable UUID getBungeeUuid() {
        return bungeeUuid;
    }

    public void UNSAFE_setBungeeUuid(UUID bungeeUuid) {
        this.bungeeUuid = bungeeUuid;
    }

    public @Nullable PlayerSkin getBungeeSkin() {
        return bungeeSkin;
    }

    public void UNSAFE_setBungeeSkin(PlayerSkin bungeeSkin) {
        this.bungeeSkin = bungeeSkin;
    }

    /**
     * Adds an entry to the plugin request map.
     * <p>
     * Only working if {@link #getConnectionState()} is {@link net.minestom.server.network.ConnectionState#LOGIN}.
     *
     * @param messageId the message id
     * @param channel   the packet channel
     * @throws IllegalStateException if a messageId with the value {@code messageId} already exists for this connection
     */
    public void addPluginRequestEntry(int messageId, @NotNull String channel) {
        if (!getConnectionState().equals(ConnectionState.LOGIN)) {
            return;
        }
        Check.stateCondition(pluginRequestMap.containsKey(messageId), "You cannot have two messageId with the same value");
        this.pluginRequestMap.put(messageId, channel);
    }

    /**
     * Gets a request channel from a message id, previously cached using {@link #addPluginRequestEntry(int, String)}.
     * <p>
     * Be aware that the internal map is cleared once the player enters the play state.
     *
     * @param messageId the message id
     * @return the channel linked to the message id, null if not found
     */
    public @Nullable String getPluginRequestChannel(int messageId) {
        return pluginRequestMap.get(messageId);
    }

    @Override
    public void setConnectionState(@NotNull ConnectionState connectionState) {
        super.setConnectionState(connectionState);
        // Clear the plugin request map (since it is not used anymore)
        if (connectionState.equals(ConnectionState.PLAY)) {
            this.pluginRequestMap.clear();
        }
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }
}
