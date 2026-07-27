package com.iridium126.createmanaindustry.hexcasting;

import java.util.List;

import com.simibubi.create.content.decoration.placard.PlacardBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Hexcasting Action: pops a {@link Vec3Iota} from the stack, reads the
 * {@link Iota} from an {@link IotaHolderItem} on a Create Depot or Placard
 * at that position, and pushes the read Iota back to the stack.
 * <p>
 * Throws {@link CMIMishapInvalidBlockOrItem} if the block at the position is
 * not a Depot/Placard, or the held item is not an IotaHolderItem, or the
 * IotaHolderItem contains no Iota.
 */
public class OpReadIotaFromBlock implements ConstMediaAction {

    public static final OpReadIotaFromBlock INSTANCE = new OpReadIotaFromBlock();

    private OpReadIotaFromBlock() {}

    @Override
    public int getArgc() {
        return 1;
    }

    @Override
    public long getMediaCost() {
        return 0;
    }

    @Override
    public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        Iota arg = args.get(0);
        if (!(arg instanceof Vec3Iota vecIota)) {
            throw MishapInvalidIota.ofType(arg, 0, "vector");
        }

        Vec3 vec = vecIota.getVec3();
        BlockPos pos = BlockPos.containing(vec);

        env.assertVecInRange(vec);

        BlockEntity be = env.getWorld().getBlockEntity(pos);
        if (be == null) {
            throw new CMIMishapInvalidBlockOrItem(vec, CMIMishapInvalidBlockOrItem.Reason.INVALID_BLOCK);
        }

        ItemStack heldItem;
        if (be instanceof DepotBlockEntity depot) {
            heldItem = depot.getHeldItem();
        } else if (be instanceof PlacardBlockEntity placard) {
            heldItem = placard.getHeldItem();
        } else {
            throw new CMIMishapInvalidBlockOrItem(vec, CMIMishapInvalidBlockOrItem.Reason.INVALID_BLOCK);
        }

        if (heldItem.isEmpty()) {
            throw new CMIMishapInvalidBlockOrItem(vec, CMIMishapInvalidBlockOrItem.Reason.EMPTY_ITEM);
        }

        if (!(heldItem.getItem() instanceof IotaHolderItem iotaHolder)) {
            throw new CMIMishapInvalidBlockOrItem(vec, CMIMishapInvalidBlockOrItem.Reason.INVALID_ITEM);
        }

        Iota readIota = iotaHolder.readIota(heldItem);
        if (readIota == null) {
            throw new CMIMishapInvalidBlockOrItem(vec, CMIMishapInvalidBlockOrItem.Reason.INVALID_ITEM);
        }

        return List.of(readIota);
    }
}
