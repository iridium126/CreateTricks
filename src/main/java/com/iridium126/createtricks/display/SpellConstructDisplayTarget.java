package com.iridium126.createtricks.display;

import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SpellConstructDisplayTarget extends DisplayTarget {

	@Override
	public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
		BlockEntity target = context.getTargetBlockEntity();
		if (target == null)
			return;

		if (line == 0)
			reserve(0, target, context);
		else if (isReserved(line, target, context))
			return;

		SpellConstructDisplayArguments.storeArgument(target, line, text);
	}

	@Override
	public DisplayTargetStats provideStats(DisplayLinkContext context) {
		return new DisplayTargetStats(SpellConstructDisplayArguments.MAX_ARGUMENTS, 256, this);
	}

	@Override
	public Component getLineOptionText(int line) {
		return Component.translatable("createtricks.display_target.argument", line + 1);
	}

	@Override
	public boolean requiresComponentSanitization() {
		return true;
	}
}
