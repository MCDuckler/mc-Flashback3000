package duckduck.flashback3000.io;

import duckduck.flashback3000.Flashback3000;
import duckduck.flashback3000.action.Action;
import duckduck.flashback3000.action.ActionRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.Objects;

public class ReplayWriter {

    private final ByteBuf dataBufferInner;
    private RegistryFriendlyByteBuf dataBuffer;
    private Reference2IntMap<Action> registeredActions;
    private Action writingAction = null;

    private RegistryAccess registryAccess;

    private int snapshotSizeWriterIndex = -1;
    private int actionSizeWriterIndex = -1;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_WRITING_SNAPSHOT = 1;
    private static final int STATE_WRITING_DATA = 2;
    public int state = STATE_EMPTY;

    public ReplayWriter(RegistryAccess registryAccess) {
        this.dataBufferInner = Unpooled.buffer();
        this.dataBuffer = new RegistryFriendlyByteBuf(this.dataBufferInner, registryAccess);
        this.registryAccess = registryAccess;
        this.writeHeader();
    }

    private void writeHeader() {
        this.registeredActions = new Reference2IntOpenHashMap<>();
        this.registeredActions.defaultReturnValue(-1);

        this.dataBuffer.writerIndex(0);
        this.dataBuffer.writeInt(Flashback3000.MAGIC);

        List<Action> actions = ActionRegistry.getActions();
        this.dataBuffer.writeVarInt(actions.size());
        for (Action action : actions) {
            this.dataBuffer.writeResourceLocation(action.name());
            this.registeredActions.put(action, this.registeredActions.size());
        }

        this.state = STATE_EMPTY;
    }

    public void setRegistryAccess(RegistryAccess registryAccess) {
        RegistryFriendlyByteBuf newDataBuffer = new RegistryFriendlyByteBuf(this.dataBufferInner, registryAccess);
        newDataBuffer.writerIndex(this.dataBuffer.writerIndex());
        newDataBuffer.readerIndex(this.dataBuffer.readerIndex());
        this.dataBuffer = newDataBuffer;
        this.registryAccess = registryAccess;
    }

    public void startSnapshot() {
        if (this.state != STATE_EMPTY) {
            throw new IllegalStateException("Can only start snapshot in STATE_EMPTY");
        }
        this.state = STATE_WRITING_SNAPSHOT;
        this.snapshotSizeWriterIndex = this.dataBuffer.writerIndex();
        this.dataBuffer.writeInt(0xDEADBEEF);
    }

    public void endSnapshot() {
        if (this.state != STATE_WRITING_SNAPSHOT) {
            throw new IllegalStateException("Can only end snapshot in STATE_WRITING_SNAPSHOT");
        }
        this.state = STATE_WRITING_DATA;
        if (this.snapshotSizeWriterIndex < 0) {
            throw new IllegalStateException("Snapshot size index unset");
        }
        int endPosition = this.dataBuffer.writerIndex();
        int written = endPosition - this.snapshotSizeWriterIndex - 4;
        this.dataBuffer.writerIndex(this.snapshotSizeWriterIndex);
        this.dataBuffer.writeInt(written);
        this.dataBuffer.writerIndex(endPosition);
        this.snapshotSizeWriterIndex = -1;
    }

    public void startAndFinishAction(Action action) {
        Objects.requireNonNull(action);
        if (this.writingAction != null) {
            throw new RuntimeException("startAndFinishAction() called while still writing " + this.writingAction.name());
        }
        int id = this.registeredActions.getInt(action);
        if (id < 0) throw new RuntimeException("Unknown action: " + action.name());
        this.dataBuffer.writeVarInt(id);
        this.dataBuffer.writeInt(0);
        this.actionSizeWriterIndex = -1;
    }

    public void startAction(Action action) {
        Objects.requireNonNull(action);
        if (this.writingAction != null) {
            throw new RuntimeException("startAction() called while still writing " + this.writingAction.name());
        }
        this.writingAction = action;
        int id = this.registeredActions.getInt(action);
        if (id < 0) throw new RuntimeException("Unknown action: " + action.name());
        this.dataBuffer.writeVarInt(id);
        this.actionSizeWriterIndex = this.dataBuffer.writerIndex();
        this.dataBuffer.writeInt(0);
    }

    public void finishAction(Action action) {
        Objects.requireNonNull(action);
        if (this.writingAction == null) {
            throw new IllegalStateException("finishAction() called before startAction()");
        }
        if (this.writingAction != action) {
            throw new IllegalStateException("finishAction() called with wrong action, expected " + this.writingAction.name() + ", got " + action.name());
        }
        this.writingAction = null;
        if (this.actionSizeWriterIndex < 0) {
            throw new IllegalStateException("Action size index unset");
        }
        int endPosition = this.dataBuffer.writerIndex();
        int written = endPosition - this.actionSizeWriterIndex - 4;
        this.dataBuffer.writerIndex(this.actionSizeWriterIndex);
        this.dataBuffer.writeInt(written);
        this.dataBuffer.writerIndex(endPosition);
        this.actionSizeWriterIndex = -1;
    }

    public RegistryFriendlyByteBuf friendlyByteBuf() {
        return this.dataBuffer;
    }

    public RegistryAccess registryAccess() {
        return this.registryAccess;
    }

    public byte[] popBytes() {
        if (this.writingAction != null) {
            throw new IllegalStateException("popBytes() called while still writing action " + this.writingAction.name());
        }
        byte[] replay = new byte[this.dataBuffer.writerIndex()];
        this.dataBuffer.getBytes(0, replay);
        this.writeHeader();
        return replay;
    }
}
