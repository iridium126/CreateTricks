package com.iridium126.createmanaindustry.compat.trickster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * Trickster fragment that stores a full Hexcasting {@link Iota}. The iota is
 * serialized with Hexcasting's own {@code IotaType.TYPED_CODEC} (NBT) and
 * gzip-compressed via {@link NbtIo} — a lossless, vanilla-only round trip.
 * <p>
 * Displayed via Trickster's default text fallback: {@link #asText()} returns
 * {@code iota.display()}, so e.g. a {@code PatternIota} renders its hex glyph
 * exactly as Hexcasting shows it (inline pattern glyphs come from the Inline
 * mod). No client renderer is registered.
 */
public final class IotaFragment implements Fragment {

    private static final String IOTA_KEY = "iota";
    private static final int WEIGHT_BASE = 8;

    public static final StructEndec<IotaFragment> ENDEC = StructEndecBuilder.of(
            Endec.BYTES.fieldOf("value", IotaFragment::getBytes),
            IotaFragment::new);

    private final byte[] bytes;
    private transient Iota cachedIota;

    public IotaFragment(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Serializes an iota with Hexcasting's TYPED_CODEC, wrapped in a CompoundTag
     *  (the encoded tag is not necessarily a compound) and gzip-compressed. */
    public static byte[] serializeIota(Iota iota) {
        try {
            Tag encoded = IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, iota).getOrThrow();
            CompoundTag root = new CompoundTag();
            root.put(IOTA_KEY, encoded);
            var out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Hexcasting iota", e);
        }
    }

    public byte[] getBytes() {
        return bytes;
    }

    public Iota getIota() {
        if (cachedIota == null) {
            try {
                CompoundTag root = NbtIo.readCompressed(
                        new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
                cachedIota = IotaType.TYPED_CODEC
                        .parse(NbtOps.INSTANCE, root.get(IOTA_KEY)).getOrThrow();
            } catch (IOException e) {
                throw new RuntimeException("Failed to deserialize Hexcasting iota", e);
            }
        }
        return cachedIota;
    }

    @Override
    public FragmentType<?> type() {
        return CMITricksterIotaRegister.iotaFragmentType();
    }

    @Override
    public Component asText() {
        return getIota().display();
    }

    @Override
    public int getWeight() {
        return WEIGHT_BASE + bytes.length;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IotaFragment other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
