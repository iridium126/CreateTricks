package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.compat.trickster.IotaFragment;

import at.petrak.hexcasting.api.casting.iota.BooleanIota;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.fragment.BooleanFragment;
import dev.enjarai.trickster.spell.fragment.ListFragment;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Lossless conversions between Hexcasting iotas and Trickster fragments,
 * leveraging CMI's own round-trip fragments:
 * <ul>
 * <li>hex → trickster: primitives map natively; anything else is wrapped in
 * {@link IotaFragment} (stored losslessly).</li>
 * <li>trickster → hex: {@link IotaFragment} unwraps; primitives map natively;
 * anything else is wrapped in a {@link TrickIota} (via a single-glyph
 * {@link SpellPart}).</li>
 * </ul>
 */
public final class FragmentConverter {

    private FragmentConverter() {
    }

    /**
     * Converts a hex iota to a trickster fragment. Never returns {@code null} —
     * unrepresentable values are wrapped losslessly in an {@link IotaFragment}.
     */
    public static Fragment iotaToFragment(Iota iota) {
        if (iota instanceof DoubleIota d) {
            try {
                return new NumberFragment(d.getDouble());
            } catch (RuntimeException e) {
                return wrapIota(iota);
            }
        }
        if (iota instanceof BooleanIota b) {
            return BooleanFragment.of(b.getBool());
        }
        if (iota instanceof Vec3Iota v) {
            try {
                var vec = v.getVec3();
                return new VectorFragment(new Vector3d(vec.x, vec.y, vec.z));
            } catch (RuntimeException e) {
                return wrapIota(iota);
            }
        }
        if (iota instanceof NullIota || iota instanceof GarbageIota) {
            return VoidFragment.INSTANCE;
        }
        if (iota instanceof ListIota list) {
            List<Fragment> fragments = new ArrayList<>();
            Iterable<Iota> subIotas = list.subIotas();
            if (subIotas != null) {
                for (Iota sub : subIotas) {
                    fragments.add(iotaToFragment(sub));
                }
            }
            return new ListFragment(fragments);
        }
        return wrapIota(iota);
    }

    private static Fragment wrapIota(Iota iota) {
        return new IotaFragment(IotaFragment.serializeIota(iota));
    }

    /**
     * Converts a trickster fragment to a hex iota. Returns {@code null} for a
     * void fragment — "no result".
     */
    @Nullable
    public static Iota fragmentToIota(Fragment fragment) {
        if (fragment == null || fragment instanceof VoidFragment) {
            return null;
        }
        if (fragment instanceof IotaFragment iotaFragment) {
            return iotaFragment.getIota();
        }
        if (fragment instanceof NumberFragment number) {
            return new DoubleIota(number.number());
        }
        if (fragment instanceof BooleanFragment bool) {
            return new BooleanIota(bool.asBoolean());
        }
        if (fragment instanceof VectorFragment vector) {
            var vec = vector.vector();
            return new Vec3Iota(new Vec3(vec.x(), vec.y(), vec.z()));
        }
        if (fragment instanceof ListFragment list) {
            List<Iota> iotas = new ArrayList<>();
            for (Fragment sub : list.fragments()) {
                Iota converted = fragmentToIota(sub);
                iotas.add(converted != null ? converted : new NullIota());
            }
            return new ListIota(iotas);
        }
        return new TrickIota(new SpellPart(fragment));
    }
}
