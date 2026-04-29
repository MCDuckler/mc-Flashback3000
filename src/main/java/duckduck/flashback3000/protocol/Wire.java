package duckduck.flashback3000.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

public final class Wire {

    private Wire() {}

    public static byte[] build(byte opcode, Consumer<DataOutputStream> writer) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(opcode);
            writer.accept(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public static void writeUUID(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUUID(DataInputStream in) throws IOException {
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    public static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    public static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1024 * 1024) {
            throw new IOException("Invalid byte array length: " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }
}
