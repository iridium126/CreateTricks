package com.iridium126.createmanaindustry.content.fluids;

import java.util.function.Consumer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Shared fluid type for this mod's molten fluids — carries the still/flowing
 * textures of the individual fluid and mirrors vanilla {@code LAVA_TYPE}'s
 * movement feel: sluggish entity motion, items sink, and the fluid uses its own
 * movement logic rather than water's.
 */
public class MoltenFluidType extends FluidType {

    private final ResourceLocation stillTexture;
    private final ResourceLocation flowingTexture;

    public MoltenFluidType(Properties properties, ResourceLocation stillTexture,
            ResourceLocation flowingTexture) {
        super(properties);
        this.stillTexture = stillTexture;
        this.flowingTexture = flowingTexture;
    }

    @Override
    public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
        consumer.accept(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return stillTexture;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return flowingTexture;
            }
        });
    }

    @Override
    public double motionScale(Entity entity) {
        return entity.level().dimensionType().ultraWarm() ? 0.007D : 0.0023333333333333335D;
    }

    @Override
    public void setItemMovement(ItemEntity entity) {
        Vec3 vec3 = entity.getDeltaMovement();
        entity.setDeltaMovement(vec3.x * 0.95F, vec3.y + (vec3.y < 0.06F ? 5.0E-4F : 0.0F), vec3.z * 0.95F);
    }

    @Override
    public boolean move(FluidState state, LivingEntity entity, Vec3 movementVector, double gravity) {
        // Replicate vanilla lava movement (LivingEntity.travel branch 2). A custom
        // FluidType is never routed to that branch (it is keyed on the LAVA_TYPE
        // identity), so we perform the movement here and signal "handled" with
        // true — otherwise travel()'s water-like branch is skipped with nothing
        // replacing it, leaving the entity immobile.
        boolean falling = entity.getDeltaMovement().y <= 0.0; // matches travel()'s flag
        double d8 = entity.getY();
        entity.moveRelative(0.02F, movementVector);
        entity.move(MoverType.SELF, entity.getDeltaMovement());
        if (entity.getFluidTypeHeight(state.getFluidType()) <= entity.getFluidJumpThreshold()) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.5, 0.8F, 0.5));
            entity.setDeltaMovement(
                    entity.getFluidFallingAdjustedMovement(gravity, falling, entity.getDeltaMovement()));
        } else {
            entity.setDeltaMovement(entity.getDeltaMovement().scale(0.5));
        }
        if (gravity != 0.0) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(0.0, -gravity / 4.0, 0.0));
        }
        Vec3 vec34 = entity.getDeltaMovement();
        if (entity.horizontalCollision && entity.isFree(vec34.x, vec34.y + 0.6F - entity.getY() + d8, vec34.z)) {
            entity.setDeltaMovement(vec34.x, 0.3F, vec34.z);
        }
        return true;
    }
}
