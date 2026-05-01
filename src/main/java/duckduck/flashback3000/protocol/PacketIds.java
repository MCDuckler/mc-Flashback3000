package duckduck.flashback3000.protocol;

public final class PacketIds {

    public static final int PROTOCOL_VERSION = 2;

    public static final String CHANNEL = "flashback3000:control";
    public static final String CHANNEL_NAMESPACE = "flashback3000";

    public static final byte HELLO = 0x01;
    public static final byte WELCOME = 0x02;

    public static final byte LIST_REPLAYS = 0x10;
    public static final byte REPLAY_LIST = 0x11;

    public static final byte START_RECORDING = 0x20;
    public static final byte STOP_RECORDING = 0x21;
    public static final byte CANCEL_RECORDING = 0x22;
    public static final byte RECORDING_STATUS = 0x23;

    public static final byte RENAME_REPLAY = 0x30;
    public static final byte DELETE_REPLAY = 0x31;
    public static final byte OPERATION_RESULT = 0x32;

    public static final byte DOWNLOAD_REQUEST = 0x40;
    public static final byte DOWNLOAD_START = 0x41;
    public static final byte DOWNLOAD_CHUNK = 0x42;
    public static final byte DOWNLOAD_ACK = 0x43;
    public static final byte DOWNLOAD_END = 0x44;

    public static final byte UPLOAD_SCENES_START = 0x50;
    public static final byte UPLOAD_SCENES_CHUNK = 0x51;
    public static final byte UPLOAD_SCENES_ACK = 0x52;
    public static final byte UPLOAD_SCENES_END = 0x53;
    public static final byte UPLOAD_SCENES_RESULT = 0x54;
    public static final byte LIST_SCENES = 0x55;
    public static final byte SCENE_LIST = 0x56;

    public static final byte PLAY_SCENE_REQUEST = 0x60;
    public static final byte CANCEL_PLAYBACK = 0x61;
    public static final byte PLAYBACK_STATUS = 0x62;

    public static final byte END_RESTORE = 0x00;
    public static final byte END_KICK    = 0x01;

    public static final byte PERM_ADMIN = 0x01;

    public static final int DOWNLOAD_CHUNK_SIZE = 32 * 1024;
    public static final int DOWNLOAD_WINDOW = 16;

    // Mojang's ServerboundCustomPayloadPacket caps payload bytes at 32767. Account for our
    // header (1 opcode + 16 UUID + 4 index + 4 length-prefix = 25 bytes) and leave margin.
    public static final int UPLOAD_CHUNK_SIZE = 30 * 1024;
    public static final int UPLOAD_WINDOW = 16;
    public static final int UPLOAD_MAX_BYTES = 4 * 1024 * 1024;

    private PacketIds() {}
}
