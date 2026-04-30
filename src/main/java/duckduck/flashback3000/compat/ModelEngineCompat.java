package duckduck.flashback3000.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ModelEngineCompat {

    private static final boolean PRESENT;

    // Always-required handles (kept from original).
    private static final Method GET_MODELED_BUKKIT;
    private static final Method IS_BASE_VISIBLE;
    private static final Method IS_RENDER_CANCELED;

    // ModeledEntity / ActiveModel / Renderer chain.
    private static final Method GET_MODELS;          // ModeledEntity.getModels() -> Map<String, ActiveModel>
    private static final Method GET_MODEL_RENDERER;  // ActiveModel.getModelRenderer() -> ModelRenderer
    private static final Class<?> DISPLAY_RENDERER_CLASS;
    private static final Method GET_PIVOT;           // DisplayRenderer.getPivot()
    private static final Method GET_HITBOX;          // DisplayRenderer.getHitbox()
    private static final Method GET_RENDERED;        // RenderQueues.getRendered() -> Map<String, DisplayBone>

    // Pivot accessors.
    private static final Method PIVOT_GET_ID;
    private static final Method PIVOT_GET_UUID;
    private static final Method PIVOT_IS_OVERRIDDEN;
    private static final Method PIVOT_GET_DYNAMIC_ID;  // -> DataTracker<Integer>
    private static final Method PIVOT_GET_POSITION;    // -> DataTracker<Vector3f>
    private static final Method PIVOT_GET_PASSENGERS;  // -> CollectionDataTracker<Integer> (extends DataTracker)

    // Hitbox accessors.
    private static final Method HB_GET_PIVOT_ID;
    private static final Method HB_GET_PIVOT_UUID;
    private static final Method HB_GET_HITBOX_ID;
    private static final Method HB_GET_HITBOX_UUID;
    private static final Method HB_GET_SHADOW_ID;
    private static final Method HB_GET_SHADOW_UUID;
    private static final Method HB_GET_POSITION;
    private static final Method HB_GET_WIDTH;
    private static final Method HB_GET_HEIGHT;
    private static final Method HB_GET_SHADOW_RADIUS;
    private static final Method HB_IS_PIVOT_VISIBLE;
    private static final Method HB_IS_HITBOX_VISIBLE;
    private static final Method HB_IS_SHADOW_VISIBLE;

    // DisplayBone accessors.
    private static final Method BONE_GET_MODEL_MAP;     // DisplayBone.getModel() -> Map<Integer, BoneData>
    private static final Method BONE_GET_POSITION;
    private static final Method BONE_GET_SCALE;
    private static final Method BONE_GET_LEFT_ROT;
    private static final Method BONE_GET_RIGHT_ROT;
    private static final Method BONE_GET_BILLBOARD;
    private static final Method BONE_GET_BRIGHTNESS;
    private static final Method BONE_GET_GLOWING;
    private static final Method BONE_GET_GLOW_COLOR;
    private static final Method BONE_GET_DISPLAY;
    private static final Method BONE_GET_VISIBILITY;

    // BoneData accessors.
    private static final Method BD_GET_ID;
    private static final Method BD_GET_UUID;
    private static final Method BD_GET_MODEL;           // -> DataTracker<ItemStack>

    // MountRenderer accessors (for vehicles with seats — emits its own pivot/mount entities).
    private static final Method GET_BEHAVIOR_RENDERER;  // ActiveModel.getBehaviorRenderer(BoneBehaviorType) -> Optional<BehaviorRenderer>
    private static final Object BONE_BEHAVIOR_TYPE_MOUNT;
    private static final Class<?> MOUNT_RENDERER_CLASS;
    private static final Method MOUNT_GET_RENDERED;     // RenderQueues.getRendered() -> Map (reused for MountRenderer too)
    private static final Method M_GET_PIVOT_ID;
    private static final Method M_GET_PIVOT_UUID;
    private static final Method M_GET_MOUNT_ID;
    private static final Method M_GET_MOUNT_UUID;
    private static final Method M_GET_POSITION;
    private static final Method M_GET_YAW;
    private static final Method M_GET_PASSENGERS;

    // DataTracker.get() — the value extractor for all the above.
    private static final Method DT_GET;

    // Default entity-data block ME emits for AREA_EFFECT_CLOUD pivots.
    // Mirrors com.ticxo.modelengine.v1_21_R6.entity.EntityUtils.DEFAULT_AREA_EFFECT_CLOUD_DATA.
    private static final List<SynchedEntityData.DataValue<?>> DEFAULT_AREA_EFFECT_CLOUD_DATA = List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 32),
            new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE),
            new SynchedEntityData.DataValue<>(8, EntityDataSerializers.FLOAT, 0.0F)
    );

    // ME's mount-pivot (ITEM_DISPLAY) default data: id 0=byte flags, 1=interp tick, 10=teleport_duration.
    private static final List<SynchedEntityData.DataValue<?>> DEFAULT_PIVOT_DISPLAY_DATA = List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 32),
            new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE),
            new SynchedEntityData.DataValue<>(10, EntityDataSerializers.INT, 1)
    );

    // ME's mount entity (ARMOR_STAND) default data: id 0=flags, 1=interp tick, 15=armor stand client flags (small=16).
    private static final List<SynchedEntityData.DataValue<?>> DEFAULT_ARMOR_STAND_DATA = List.of(
            new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 32),
            new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE),
            new SynchedEntityData.DataValue<>(15, EntityDataSerializers.BYTE, (byte) 16)
    );

    static {
        boolean present = false;
        Method getModeled = null, isVisible = null, renderCanceled = null;
        Method getModels = null, getModelRenderer = null;
        Class<?> displayRendererClass = null;
        Method getPivot = null, getHitbox = null, getRendered = null;
        Method pId = null, pUuid = null, pOver = null, pDyn = null, pPos = null, pPass = null;
        Method hPivotId = null, hPivotUuid = null, hHitId = null, hHitUuid = null, hShadId = null, hShadUuid = null;
        Method hPos = null, hWidth = null, hHeight = null, hShadRad = null;
        Method hPVis = null, hHVis = null, hSVis = null;
        Method boneModelMap = null, bonePos = null, boneScale = null, boneLRot = null, boneRRot = null;
        Method boneBillboard = null, boneBright = null, boneGlow = null, boneGlowColor = null;
        Method boneDisplay = null, boneVis = null;
        Method bdId = null, bdUuid = null, bdModel = null;
        Method dtGet = null;
        Method getBehaviorRenderer = null;
        Object boneBehaviorTypeMount = null;
        Class<?> mountRendererClass = null;
        Method mountGetRendered = null;
        Method mPId = null, mPUuid = null, mMId = null, mMUuid = null;
        Method mPos = null, mYaw = null, mPass = null;

        try {
            Class<?> apiClass = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            getModeled = apiClass.getMethod("getModeledEntity", org.bukkit.entity.Entity.class);
            renderCanceled = apiClass.getMethod("isRenderCanceled", int.class);

            Class<?> modeledClass = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
            isVisible = modeledClass.getMethod("isBaseEntityVisible");
            getModels = modeledClass.getMethod("getModels");

            Class<?> activeModelClass = Class.forName("com.ticxo.modelengine.api.model.ActiveModel");
            getModelRenderer = activeModelClass.getMethod("getModelRenderer");

            displayRendererClass = Class.forName("com.ticxo.modelengine.api.model.render.DisplayRenderer");
            getPivot = displayRendererClass.getMethod("getPivot");
            getHitbox = displayRendererClass.getMethod("getHitbox");
            // getRendered is on RenderQueues which DisplayRenderer extends.
            Class<?> renderQueues = Class.forName("com.ticxo.modelengine.api.model.bone.render.renderer.RenderQueues");
            getRendered = renderQueues.getMethod("getRendered");

            Class<?> pivotIface = Class.forName("com.ticxo.modelengine.api.model.render.DisplayRenderer$Pivot");
            pId = pivotIface.getMethod("getId");
            pUuid = pivotIface.getMethod("getUuid");
            pOver = pivotIface.getMethod("isOverridden");
            pDyn = pivotIface.getMethod("getDynamicId");
            pPos = pivotIface.getMethod("getPosition");
            pPass = pivotIface.getMethod("getPassengers");

            Class<?> hitboxIface = Class.forName("com.ticxo.modelengine.api.model.render.DisplayRenderer$Hitbox");
            hPivotId = hitboxIface.getMethod("getPivotId");
            hPivotUuid = hitboxIface.getMethod("getPivotUuid");
            hHitId = hitboxIface.getMethod("getHitboxId");
            hHitUuid = hitboxIface.getMethod("getHitboxUuid");
            hShadId = hitboxIface.getMethod("getShadowId");
            hShadUuid = hitboxIface.getMethod("getShadowUuid");
            hPos = hitboxIface.getMethod("getPosition");
            hWidth = hitboxIface.getMethod("getWidth");
            hHeight = hitboxIface.getMethod("getHeight");
            hShadRad = hitboxIface.getMethod("getShadowRadius");
            hPVis = hitboxIface.getMethod("isPivotVisible");
            hHVis = hitboxIface.getMethod("isHitboxVisible");
            hSVis = hitboxIface.getMethod("isShadowVisible");

            Class<?> boneIface = Class.forName("com.ticxo.modelengine.api.model.render.DisplayBone");
            boneModelMap = boneIface.getMethod("getModel");
            bonePos = boneIface.getMethod("getPosition");
            boneScale = boneIface.getMethod("getScale");
            boneLRot = boneIface.getMethod("getLeftRotation");
            boneRRot = boneIface.getMethod("getRightRotation");
            boneBillboard = boneIface.getMethod("getBillboard");
            boneBright = boneIface.getMethod("getBrightness");
            boneGlow = boneIface.getMethod("getGlowing");
            boneGlowColor = boneIface.getMethod("getGlowColor");
            boneDisplay = boneIface.getMethod("getDisplay");
            boneVis = boneIface.getMethod("getVisibility");

            Class<?> bdIface = Class.forName("com.ticxo.modelengine.api.model.render.DisplayBone$BoneData");
            bdId = bdIface.getMethod("getId");
            bdUuid = bdIface.getMethod("getUuid");
            bdModel = bdIface.getMethod("getModel");

            Class<?> dtIface = Class.forName("com.ticxo.modelengine.api.utils.data.tracker.DataTracker");
            dtGet = dtIface.getMethod("get");

            // MountRenderer access (optional — vehicles with seats need this).
            Class<?> behaviorTypeClass = Class.forName("com.ticxo.modelengine.api.model.bone.BoneBehaviorType");
            getBehaviorRenderer = activeModelClass.getMethod("getBehaviorRenderer", behaviorTypeClass);
            Class<?> behaviorTypesClass = Class.forName("com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes");
            boneBehaviorTypeMount = behaviorTypesClass.getField("MOUNT").get(null);
            mountRendererClass = Class.forName("com.ticxo.modelengine.api.model.bone.render.renderer.MountRenderer");
            mountGetRendered = renderQueues.getMethod("getRendered");  // same getter, different generic type
            Class<?> mountIface = Class.forName("com.ticxo.modelengine.api.model.bone.render.renderer.MountRenderer$Mount");
            mPId = mountIface.getMethod("getPivotId");
            mPUuid = mountIface.getMethod("getPivotUuid");
            mMId = mountIface.getMethod("getMountId");
            mMUuid = mountIface.getMethod("getMountUuid");
            mPos = mountIface.getMethod("getPosition");
            mYaw = mountIface.getMethod("getYaw");
            mPass = mountIface.getMethod("getPassengers");

            present = true;
        } catch (Throwable ignored) {}

        PRESENT = present;
        GET_MODELED_BUKKIT = getModeled;
        IS_BASE_VISIBLE = isVisible;
        IS_RENDER_CANCELED = renderCanceled;
        GET_MODELS = getModels;
        GET_MODEL_RENDERER = getModelRenderer;
        DISPLAY_RENDERER_CLASS = displayRendererClass;
        GET_PIVOT = getPivot;
        GET_HITBOX = getHitbox;
        GET_RENDERED = getRendered;
        PIVOT_GET_ID = pId; PIVOT_GET_UUID = pUuid; PIVOT_IS_OVERRIDDEN = pOver;
        PIVOT_GET_DYNAMIC_ID = pDyn; PIVOT_GET_POSITION = pPos; PIVOT_GET_PASSENGERS = pPass;
        HB_GET_PIVOT_ID = hPivotId; HB_GET_PIVOT_UUID = hPivotUuid;
        HB_GET_HITBOX_ID = hHitId; HB_GET_HITBOX_UUID = hHitUuid;
        HB_GET_SHADOW_ID = hShadId; HB_GET_SHADOW_UUID = hShadUuid;
        HB_GET_POSITION = hPos; HB_GET_WIDTH = hWidth; HB_GET_HEIGHT = hHeight; HB_GET_SHADOW_RADIUS = hShadRad;
        HB_IS_PIVOT_VISIBLE = hPVis; HB_IS_HITBOX_VISIBLE = hHVis; HB_IS_SHADOW_VISIBLE = hSVis;
        BONE_GET_MODEL_MAP = boneModelMap;
        BONE_GET_POSITION = bonePos; BONE_GET_SCALE = boneScale;
        BONE_GET_LEFT_ROT = boneLRot; BONE_GET_RIGHT_ROT = boneRRot;
        BONE_GET_BILLBOARD = boneBillboard; BONE_GET_BRIGHTNESS = boneBright;
        BONE_GET_GLOWING = boneGlow; BONE_GET_GLOW_COLOR = boneGlowColor;
        BONE_GET_DISPLAY = boneDisplay; BONE_GET_VISIBILITY = boneVis;
        BD_GET_ID = bdId; BD_GET_UUID = bdUuid; BD_GET_MODEL = bdModel;
        DT_GET = dtGet;
        GET_BEHAVIOR_RENDERER = getBehaviorRenderer;
        BONE_BEHAVIOR_TYPE_MOUNT = boneBehaviorTypeMount;
        MOUNT_RENDERER_CLASS = mountRendererClass;
        MOUNT_GET_RENDERED = mountGetRendered;
        M_GET_PIVOT_ID = mPId; M_GET_PIVOT_UUID = mPUuid;
        M_GET_MOUNT_ID = mMId; M_GET_MOUNT_UUID = mMUuid;
        M_GET_POSITION = mPos; M_GET_YAW = mYaw; M_GET_PASSENGERS = mPass;
    }

    private ModelEngineCompat() {}

    public static boolean isPresent() { return PRESENT; }

    private static Object getModeled(Entity nmsEntity) {
        if (!PRESENT) return null;
        try {
            return GET_MODELED_BUKKIT.invoke(null, nmsEntity.getBukkitEntity());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Mirrors ModelEngineChannelHandler.shouldShow — returns true if ModelEngine
     * would suppress every clientbound packet for this entity from the recording
     * player's view.
     */
    public static boolean shouldHideBase(Entity nmsEntity) {
        if (!PRESENT) return false;
        try {
            if ((Boolean) IS_RENDER_CANCELED.invoke(null, nmsEntity.getId())) return true;
            Object modeled = getModeled(nmsEntity);
            if (modeled == null) return false;
            return !(Boolean) IS_BASE_VISIBLE.invoke(modeled);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True if the entity is part of a ModelEngine model assembly — either a
     * Display rendering a bone, or a base mob with an associated ModeledEntity.
     */
    public static boolean isMERelated(Entity nmsEntity) {
        if (nmsEntity instanceof net.minecraft.world.entity.Display) return true;
        return getModeled(nmsEntity) != null;
    }

    /**
     * Build the full ModelEngine spawn packet bundle for this base entity, mirroring
     * what {@code DisplayParser.spawn} would emit on a fresh tracker pairing.
     *
     * <p>Output (per active model on the entity):
     * <ul>
     *   <li>pivot AddEntity (AREA_EFFECT_CLOUD) + default pivot SetEntityData (unless overridden)</li>
     *   <li>per bone: AddEntity (ITEM_DISPLAY) + SetEntityData with ALL transform / display fields</li>
     *   <li>pivotMount SetPassengers (binding all bone IDs to pivot's dynamic id)</li>
     *   <li>hitbox pivot AddEntity + default data</li>
     *   <li>hitbox INTERACTION + interaction width/height (if visible)</li>
     *   <li>shadow ITEM_DISPLAY + shadow radius (if visible)</li>
     *   <li>hitbox-pivot SetPassengers binding hitbox+shadow</li>
     * </ul>
     *
     * Used by the recorder snapshot path. Replaces the previous tracker-re-pair flow
     * which depended on ME's async render queue and dropped the bundle when the
     * drain window expired.
     *
     * <p>Returns an empty list if ModelEngine is not present, the entity has no
     * ModeledEntity, or any reflection step fails.
     */
    public static List<Packet<? super ClientGamePacketListener>> buildSpawnPackets(Entity nmsBase) {
        if (!PRESENT) return Collections.emptyList();
        Object modeled = getModeled(nmsBase);
        if (modeled == null) return Collections.emptyList();
        List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
        try {
            Map<?, ?> models = (Map<?, ?>) GET_MODELS.invoke(modeled);
            if (models == null || models.isEmpty()) return Collections.emptyList();
            for (Object activeModel : models.values()) {
                // Per-model try/catch so one bad ActiveModel doesn't lose the whole entity's bundle.
                try {
                    Object renderer = GET_MODEL_RENDERER.invoke(activeModel);
                    if (renderer != null && DISPLAY_RENDERER_CLASS.isInstance(renderer)) {
                        buildOneModel(out, renderer);
                    }
                    buildMounts(out, activeModel);
                } catch (Throwable mt) {
                    duckduck.flashback3000.Flashback3000.getInstance().getLogger()
                            .warning("ME model spawn build failed for entity id " + nmsBase.getId() + ": " + mt);
                }
            }
            return out;
        } catch (Throwable t) {
            duckduck.flashback3000.Flashback3000.getInstance().getLogger()
                    .warning("ME spawn build outer failure for entity id " + nmsBase.getId() + ": " + t);
            return out;  // return whatever we managed to build before the throw
        }
    }

    private static void buildOneModel(List<Packet<? super ClientGamePacketListener>> out, Object renderer) throws Throwable {
        Object pivot = GET_PIVOT.invoke(renderer);
        Object hitbox = GET_HITBOX.invoke(renderer);
        Map<?, ?> bones = (Map<?, ?>) GET_RENDERED.invoke(renderer);

        boolean pivotOverridden = (Boolean) PIVOT_IS_OVERRIDDEN.invoke(pivot);
        int pivotId = (Integer) PIVOT_GET_ID.invoke(pivot);
        UUID pivotUuid = (UUID) PIVOT_GET_UUID.invoke(pivot);
        int pivotDynamicId = (Integer) DT_GET.invoke(PIVOT_GET_DYNAMIC_ID.invoke(pivot));
        Vector3f pivotPos = (Vector3f) DT_GET.invoke(PIVOT_GET_POSITION.invoke(pivot));
        Collection<?> passengerObjs = (Collection<?>) DT_GET.invoke(PIVOT_GET_PASSENGERS.invoke(pivot));

        // pivotSpawn / pivotData (skipped if pivot is "overridden" — ME piggybacks on another entity).
        if (!pivotOverridden) {
            out.add(new ClientboundAddEntityPacket(
                    pivotId, pivotUuid,
                    pivotPos.x, pivotPos.y - 0.5, pivotPos.z,
                    0f, 0f, EntityType.AREA_EFFECT_CLOUD, 0, Vec3.ZERO, 0));
            out.add(new ClientboundSetEntityDataPacket(pivotId, DEFAULT_AREA_EFFECT_CLOUD_DATA));
        }

        // Bone displays.
        if (bones != null) {
            for (Object bone : bones.values()) {
                Map<?, ?> boneDataMap = (Map<?, ?>) BONE_GET_MODEL_MAP.invoke(bone);
                if (boneDataMap == null) continue;
                for (Object boneData : boneDataMap.values()) {
                    int bId = (Integer) BD_GET_ID.invoke(boneData);
                    UUID bUuid = (UUID) BD_GET_UUID.invoke(boneData);
                    out.add(new ClientboundAddEntityPacket(
                            bId, bUuid,
                            pivotPos.x, pivotPos.y, pivotPos.z,
                            0f, 0f, EntityType.ITEM_DISPLAY, 0, Vec3.ZERO, 0));
                    out.add(buildBoneData(bone, boneData, bId));
                }
            }
        }

        // pivotMount: bind all bone IDs as passengers of the pivot's dynamic id.
        // dynamicId may differ from the literal pivot id when ME piggybacks the pivot
        // role onto another entity (e.g., a base armor stand).
        int[] passengerIds = new int[passengerObjs.size()];
        int i = 0;
        for (Object o : passengerObjs) passengerIds[i++] = (Integer) o;
        out.add(buildSetPassengers(pivotDynamicId, passengerIds));

        // Hitbox / shadow (fire is omitted — rarely visible at snapshot time and would
        // bloat the spawn bundle; ME's natural updateRealtime restores it post-snapshot).
        buildHitbox(out, hitbox);
    }

    /**
     * Mirrors {@code MountParser.spawn} per active mount: emits the mount-pivot
     * (ITEM_DISPLAY) and the mount entity (ARMOR_STAND), plus the SetPassengers
     * chain that binds {@code mount-pivot → mount → [actual passengers]}.
     *
     * <p>Without this, vehicles using ME's mount renderer (UltraCars seats etc.)
     * have a model pivot whose dynamicId points at a mount-pivot ID that never
     * exists on playback — so SetPassengers binding the model bones to that
     * dynamicId silently fails and bones stay frozen at spawn position.
     */
    private static void buildMounts(List<Packet<? super ClientGamePacketListener>> out, Object activeModel) throws Throwable {
        if (GET_BEHAVIOR_RENDERER == null || BONE_BEHAVIOR_TYPE_MOUNT == null) return;
        Object maybeRenderer = GET_BEHAVIOR_RENDERER.invoke(activeModel, BONE_BEHAVIOR_TYPE_MOUNT);
        if (maybeRenderer == null) return;
        // Optional<BehaviorRenderer> — unwrap.
        java.util.Optional<?> opt = (java.util.Optional<?>) maybeRenderer;
        if (opt.isEmpty()) return;
        Object mountRenderer = opt.get();
        if (!MOUNT_RENDERER_CLASS.isInstance(mountRenderer)) return;

        Map<?, ?> mounts = (Map<?, ?>) MOUNT_GET_RENDERED.invoke(mountRenderer);
        if (mounts == null) return;

        for (Object mount : mounts.values()) {
            int pivotId = (Integer) M_GET_PIVOT_ID.invoke(mount);
            UUID pivotUuid = (UUID) M_GET_PIVOT_UUID.invoke(mount);
            int mountId = (Integer) M_GET_MOUNT_ID.invoke(mount);
            UUID mountUuid = (UUID) M_GET_MOUNT_UUID.invoke(mount);
            Vector3f pos = (Vector3f) DT_GET.invoke(M_GET_POSITION.invoke(mount));
            byte yawByte = (Byte) DT_GET.invoke(M_GET_YAW.invoke(mount));
            float yawDeg = byteToRot(yawByte);
            @SuppressWarnings("unchecked")
            Collection<Integer> passengerObjs = (Collection<Integer>) DT_GET.invoke(M_GET_PASSENGERS.invoke(mount));

            // Mount-pivot: ITEM_DISPLAY at the mount position.
            out.add(new ClientboundAddEntityPacket(
                    pivotId, pivotUuid,
                    pos.x, pos.y, pos.z,
                    0f, 0f, EntityType.ITEM_DISPLAY, 0, Vec3.ZERO, 0));
            out.add(new ClientboundSetEntityDataPacket(pivotId, DEFAULT_PIVOT_DISPLAY_DATA));

            // Mount entity: ARMOR_STAND with current yaw.
            out.add(new ClientboundAddEntityPacket(
                    mountId, mountUuid,
                    pos.x, pos.y, pos.z,
                    0f, yawDeg, EntityType.ARMOR_STAND, 0, Vec3.ZERO, 0));
            out.add(new ClientboundSetEntityDataPacket(mountId, DEFAULT_ARMOR_STAND_DATA));

            // SetPassengers: pivot has the mount as its single passenger; the mount
            // has the actual rider entity ids (typically the player riding).
            out.add(buildSetPassengers(pivotId, new int[]{mountId}));
            int[] actualPassengers = new int[passengerObjs.size()];
            int i = 0;
            for (Integer id : passengerObjs) actualPassengers[i++] = id;
            out.add(buildSetPassengers(mountId, actualPassengers));
        }
    }

    /** Mirrors ME's TMath.byteToRot — convert NMS byte angle to float degrees. */
    private static float byteToRot(byte b) {
        return (b & 0xFF) * 360f / 256f;
    }

    private static ClientboundSetEntityDataPacket buildBoneData(Object bone, Object boneData, int boneId) throws Throwable {
        List<SynchedEntityData.DataValue<?>> v = new ArrayList<>(14);

        // Force-spawn shape (mirrors DisplayParser.displayData with force=true):
        // 1=interpolation start tick, 8=interpolation duration, 9=teleport duration / step.
        v.add(new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE));
        v.add(new SynchedEntityData.DataValue<>(8, EntityDataSerializers.INT, 0));
        v.add(new SynchedEntityData.DataValue<>(9, EntityDataSerializers.INT, 1));

        boolean glowing = (Boolean) DT_GET.invoke(BONE_GET_GLOWING.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) (glowing ? 96 : 32)));

        int glowColor = (Integer) DT_GET.invoke(BONE_GET_GLOW_COLOR.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(22, EntityDataSerializers.INT, glowColor));

        int brightness = (Integer) DT_GET.invoke(BONE_GET_BRIGHTNESS.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(16, EntityDataSerializers.INT, brightness));

        Vector3f position = (Vector3f) DT_GET.invoke(BONE_GET_POSITION.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(11, EntityDataSerializers.VECTOR3, position));

        Vector3f scale = (Vector3f) DT_GET.invoke(BONE_GET_SCALE.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(12, EntityDataSerializers.VECTOR3, scale));

        Quaternionf left = (Quaternionf) DT_GET.invoke(BONE_GET_LEFT_ROT.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(13, EntityDataSerializers.QUATERNION, left));

        Quaternionf right = (Quaternionf) DT_GET.invoke(BONE_GET_RIGHT_ROT.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(14, EntityDataSerializers.QUATERNION, right));

        Object billboard = DT_GET.invoke(BONE_GET_BILLBOARD.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(15, EntityDataSerializers.BYTE, (byte) ((Enum<?>) billboard).ordinal()));

        boolean visible = (Boolean) DT_GET.invoke(BONE_GET_VISIBILITY.invoke(bone));
        v.add(new SynchedEntityData.DataValue<>(17, EntityDataSerializers.FLOAT, visible ? 4096.0f : 0.0f));

        Object bukkitItem = DT_GET.invoke(BD_GET_MODEL.invoke(boneData));
        net.minecraft.world.item.ItemStack nmsItem = bukkitItem == null
                ? net.minecraft.world.item.ItemStack.EMPTY
                : CraftItemStack.asNMSCopy((org.bukkit.inventory.ItemStack) bukkitItem);
        v.add(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.ITEM_STACK, nmsItem));

        Object display = DT_GET.invoke(BONE_GET_DISPLAY.invoke(bone));
        byte displayOrdinal = display == null ? 0 : (byte) ((Enum<?>) display).ordinal();
        v.add(new SynchedEntityData.DataValue<>(24, EntityDataSerializers.BYTE, displayOrdinal));

        return new ClientboundSetEntityDataPacket(boneId, v);
    }

    private static void buildHitbox(List<Packet<? super ClientGamePacketListener>> out, Object hitbox) throws Throwable {
        boolean pivotVisible = (Boolean) HB_IS_PIVOT_VISIBLE.invoke(hitbox);
        if (!pivotVisible) return;

        int hPivotId = (Integer) HB_GET_PIVOT_ID.invoke(hitbox);
        UUID hPivotUuid = (UUID) HB_GET_PIVOT_UUID.invoke(hitbox);
        Vector3f hPos = (Vector3f) DT_GET.invoke(HB_GET_POSITION.invoke(hitbox));
        boolean hitboxVisible = (Boolean) HB_IS_HITBOX_VISIBLE.invoke(hitbox);
        boolean shadowVisible = (Boolean) HB_IS_SHADOW_VISIBLE.invoke(hitbox);

        // hitbox pivot = AREA_EFFECT_CLOUD (-0.5 y offset, same as model pivot).
        out.add(new ClientboundAddEntityPacket(
                hPivotId, hPivotUuid,
                hPos.x, hPos.y - 0.5, hPos.z,
                0f, 0f, EntityType.AREA_EFFECT_CLOUD, 0, Vec3.ZERO, 0));
        out.add(new ClientboundSetEntityDataPacket(hPivotId, DEFAULT_AREA_EFFECT_CLOUD_DATA));

        List<Integer> mountPassengers = new ArrayList<>(2);

        if (hitboxVisible) {
            int hitboxId = (Integer) HB_GET_HITBOX_ID.invoke(hitbox);
            UUID hitboxUuid = (UUID) HB_GET_HITBOX_UUID.invoke(hitbox);
            float width = (Float) DT_GET.invoke(HB_GET_WIDTH.invoke(hitbox));
            float height = (Float) DT_GET.invoke(HB_GET_HEIGHT.invoke(hitbox));
            out.add(new ClientboundAddEntityPacket(
                    hitboxId, hitboxUuid,
                    hPos.x, hPos.y, hPos.z,
                    0f, 0f, EntityType.INTERACTION, 0, Vec3.ZERO, 0));
            // Interaction entity data: 1=interp tick, 10=response, 8=width, 9=height.
            List<SynchedEntityData.DataValue<?>> data = new ArrayList<>(4);
            data.add(new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE));
            data.add(new SynchedEntityData.DataValue<>(10, EntityDataSerializers.BOOLEAN, false));
            data.add(new SynchedEntityData.DataValue<>(8, EntityDataSerializers.FLOAT, width));
            data.add(new SynchedEntityData.DataValue<>(9, EntityDataSerializers.FLOAT, height));
            out.add(new ClientboundSetEntityDataPacket(hitboxId, data));
            mountPassengers.add(hitboxId);
        }

        if (shadowVisible) {
            int shadowId = (Integer) HB_GET_SHADOW_ID.invoke(hitbox);
            UUID shadowUuid = (UUID) HB_GET_SHADOW_UUID.invoke(hitbox);
            float radius = (Float) DT_GET.invoke(HB_GET_SHADOW_RADIUS.invoke(hitbox));
            out.add(new ClientboundAddEntityPacket(
                    shadowId, shadowUuid,
                    hPos.x, hPos.y, hPos.z,
                    0f, 0f, EntityType.ITEM_DISPLAY, 0, Vec3.ZERO, 0));
            // Item display: 1=interp tick, 18=shadow_radius.
            List<SynchedEntityData.DataValue<?>> data = new ArrayList<>(2);
            data.add(new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, Integer.MAX_VALUE));
            data.add(new SynchedEntityData.DataValue<>(18, EntityDataSerializers.FLOAT, radius));
            out.add(new ClientboundSetEntityDataPacket(shadowId, data));
            mountPassengers.add(shadowId);
        }

        if (!mountPassengers.isEmpty()) {
            int[] ids = new int[mountPassengers.size()];
            for (int i = 0; i < mountPassengers.size(); i++) ids[i] = mountPassengers.get(i);
            out.add(buildSetPassengers(hPivotId, ids));
        }
    }

    /**
     * The public ClientboundSetPassengersPacket constructor takes an Entity to read
     * vehicle id and passenger ids from. We don't have a real Entity here (pivots are
     * fake — packet-only), so we go through the stream codec: write the wire format
     * directly, then decode back into the packet object.
     */
    private static ClientboundSetPassengersPacket buildSetPassengers(int vehicleId, int[] passengerIds) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(vehicleId);
        buf.writeVarIntArray(passengerIds);
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
    }
}
