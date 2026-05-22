package com.iridium126.createtricks.display;

import java.util.List;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ModularSpellConstructDisplayTarget extends SpellConstructDisplayTarget {

	@Override
	public DisplayTargetStats provideStats(DisplayLinkContext context) {
		int rows = SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS * SpellConstructDisplayArguments.MAX_ARGUMENTS;
		return new DisplayTargetStats(rows, 256, this);
	}

	@Override
	public Component getLineOptionText(int line) {
		int core = SpellConstructDisplayArguments.decodeModularExecutorSlot(line) + 1;
		int argument = SpellConstructDisplayArguments.decodeModularArgumentIndex(line) + 1;
		return Component.translatable("createtricks.display_target.modular_argument", core, argument);
	}

	@Override
	public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
		BlockEntity target = context.getTargetBlockEntity();
		if (target == null)
			return;

		if (line == 0)
			reserve(0, target, context);
		else if (isReserved(line, target, context))
			return;

		SpellConstructDisplayArguments.storeModularArgument(
				target,
				SpellConstructDisplayArguments.decodeModularExecutorSlot(line),
				SpellConstructDisplayArguments.decodeModularArgumentIndex(line),
				text);
	}
}
