package com.iridium126.createtricks.content.kinetics;

import static net.minecraft.ChatFormatting.AQUA;
import static net.minecraft.ChatFormatting.GRAY;

import java.util.List;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.simibubi.create.foundation.utility.CreateLang;

import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public class StressRangeTooltipModifier implements TooltipModifier {
	private final int minStressPerRpm;
	private final int maxStressPerRpm;

	public StressRangeTooltipModifier(int minStressPerRpm, int maxStressPerRpm) {
		this.minStressPerRpm = minStressPerRpm;
		this.maxStressPerRpm = maxStressPerRpm;
	}

	@Override
	public void modify(ItemTooltipEvent context) {
		if (!StressImpact.isEnabled()) {
			return;
		}

		boolean hasGoggles = context.getEntity() != null && GogglesItem.isWearingGoggles(context.getEntity());
		List<Component> tooltip = context.getToolTip();
		tooltip.add(CommonComponents.EMPTY);

		CreateLang.translate("tooltip.stressImpact")
				.style(GRAY)
				.addTo(tooltip);

		LangBuilder builder = CreateLang.builder()
				.add(CreateLang.text(TooltipHelper.makeProgressBar(3, 2))
						.style(AQUA));

		if (hasGoggles) {
			builder.text(" ")
					.add(Component.translatable("tooltip.createtricks.stress_range", minStressPerRpm, maxStressPerRpm))
					.add(Component.literal(" ").append(Component.translatable("tooltip.createtricks.variable_impact")));
		} else {
			builder.text(" ")
					.add(Component.translatable("tooltip.createtricks.variable"));
		}

		builder.addTo(tooltip);
	}
}
