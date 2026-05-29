package com.iridium126.createtricks.trickster;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import com.iridium126.createtricks.Config;
import com.iridium126.createtricks.CreateTricks;
import com.iridium126.createtricks.content.kinetics.TemporaryStress;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.enjarai.trickster.spell.trick.Trick;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class KineticStressTrickRegister {
	private static final float STRESS_PER_SPEED = 4;
	private static volatile boolean registered;

	private KineticStressTrickRegister() {
	}

	public static void register() {
		if (registered)
			return;

		try {
			if (!TricksterReflection.ensureRegisterInit()) {
				CreateTricks.LOGGER.warn("Temporary kinetic stress trick registration is unavailable");
				return;
			}

			Object pattern = TricksterReflection.patternOfMethod.invoke(null,
					(Object) new int[] { 1, 0, 2, 3, 4, 5, 6, 8, 7 });
			Object trick = TricksterReflection.loadArgumentTrickCtor.newInstance(pattern, 0);

			Method getSignaturesMethod = trick.getClass().getMethod("getSignatures");
			Object rawSignatures = getSignaturesMethod.invoke(trick);
			if (!(rawSignatures instanceof List<?> signaturesRaw))
				return;

			@SuppressWarnings("unchecked")
			List<Object> signatures = (List<Object>) signaturesRaw;
			signatures.clear();
			signatures.add(createSignatureProxy());
			TricksterReflection.tricksRegisterMethod.invoke(null, "temporary_kinetic_stress", trick);
			registered = true;
			CreateTricks.LOGGER.info("Registered Trickster trick: temporary_kinetic_stress");
		} catch (Throwable t) {
			CreateTricks.LOGGER.warn("Failed to register temporary kinetic stress trick", t);
		}
	}

	private static Object createSignatureProxy() {
		java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
			String name = method.getName();
			if ("match".equals(name))
				return matches(args);
			if ("asText".equals(name))
				return TricksterReflection.makeText("vector, number, number -> vector");
			if ("run".equals(name))
				return run(args);
			return method.getDefaultValue();
		};
		return Proxy.newProxyInstance(
				TricksterReflection.signatureClass.getClassLoader(),
				new Class<?>[] { TricksterReflection.signatureClass },
				handler);
	}

	private static boolean matches(Object[] args) {
		if (args == null || args.length == 0 || !(args[0] instanceof List<?> fragments))
			return false;
		if (fragments.size() != 3)
			return false;

		return TricksterReflection.vectorFragmentClass.isInstance(fragments.get(0))
				&& TricksterReflection.numberFragmentClass.isInstance(fragments.get(1))
				&& TricksterReflection.numberFragmentClass.isInstance(fragments.get(2));
	}

	private static Object run(Object[] args) throws Throwable {
		Trick<?> trick = (Trick<?>) args[0];
		Object spellContext = args[1];
		@SuppressWarnings("unchecked")
		List<Object> fragments = (List<Object>) args[2];

		Object vectorFragment = fragments.get(0);
		double speedInput = (double) TricksterReflection.numberFragmentNumberMethod.invoke(fragments.get(1));
		double durationInput = (double) TricksterReflection.numberFragmentNumberMethod.invoke(fragments.get(2));
		float stressMagnitude = (float) Math.abs(speedInput) * STRESS_PER_SPEED;
		int durationTicks = (int) Math.floor(durationInput);
		if (stressMagnitude <= 0 || durationTicks <= 0)
			throw new InvalidKineticStressBlunder(trick);

		BlockPos pos = (BlockPos) TricksterReflection.vectorFragmentToBlockPosMethod.invoke(vectorFragment);
		Object source = TricksterReflection.spellContextSourceMethod.invoke(spellContext);
		Object world = TricksterReflection.spellSourceGetWorldMethod.invoke(source);
		if (!(world instanceof ServerLevel level))
			throw new InvalidKineticTargetBlunder(trick, pos);

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof KineticBlockEntity kinetic))
			throw new InvalidKineticTargetBlunder(trick, pos);

		float manaCost = (float) (Config.manaPerStress * stressMagnitude * durationTicks);
		TricksterReflection.spellContextUseManaMethod.invoke(spellContext, trick, manaCost);
		TemporaryStress.apply(kinetic, speedInput < 0 ? -stressMagnitude : stressMagnitude, durationTicks);
		return vectorFragment;
	}
}
