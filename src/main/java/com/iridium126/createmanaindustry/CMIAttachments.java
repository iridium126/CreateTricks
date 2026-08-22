package com.iridium126.createmanaindustry;

import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore.MistFieldData;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsStore;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge data attachments for CreateManaIndustry.
 * <p>
 * {@link #MIST_FIELD} attaches the per-dimension mist field data to each
 * {@link net.minecraft.world.level.Level}. The data is created lazily on first
 * access and lives exactly as long as the level does — the server holds it on
 * its {@code ServerLevel}, the client on its own (empty) {@code ClientLevel} —
 * so no static per-dimension maps or dimension-unload hooks are needed.
 * <p>
 * {@link #TEMPORARY_KINETICS} follows the same pattern for the temporary
 * kinetics system, additionally serializing active states with the level save.
 */
public final class CMIAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateManaIndustry.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MistFieldData>> MIST_FIELD =
            REGISTER.register("mist_field", () -> AttachmentType.builder(MistFieldData::new).build());

    /**
     * Active temporary kinetics states keyed by packed block position. The
     * serializer persists remaining durations server-side; client levels keep
     * their packet-mirrored copy in memory only.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<TemporaryKineticsStore>> TEMPORARY_KINETICS =
            REGISTER.register("temporary_kinetics",
                    () -> AttachmentType.builder(TemporaryKineticsStore::new)
                            .serialize(new TemporaryKineticsStore.Serializer())
                            .build());

    private CMIAttachments() {}

    public static void register(IEventBus bus) {
        REGISTER.register(bus);
    }
}
