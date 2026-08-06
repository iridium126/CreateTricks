package com.iridium126.createmanaindustry.mixin.trickster;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.execution.source.SpellSource;

/**
 * Routes Trickster's mana pre-checks through the override-aware
 * {@code SpellContext.getManaStorage()} instead of the raw
 * {@code source.getManaStorage()}.
 * <p>
 * Normally both return the same storage, so behavior is unchanged; when CMI
 * injects a pool override ({@code execute_trick} in a hex circle), the check
 * and the actual spend now read the same slate-knot pool. The target method
 * descriptors contain no Minecraft classes (Trick/ManaVariant/Storage are
 * mod/fabric names), so they are mapping-independent.
 * <p>
 * Implemented as a {@code @Redirect} on the {@code source} field read (the
 * handler receives the {@link SpellContext} instance) which returns a dynamic
 * proxy delegating everything to the real source except
 * {@code getManaStorage()}.
 */
@Mixin(targets = "dev.enjarai.trickster.spell.SpellContext")
public class SpellContextMixin {

    @Redirect(method = {
            "checkScaledMana(Ldev/enjarai/trickster/spell/trick/Trick;Ldev/enjarai/trickster/spell/mana/storage/ManaVariant;DLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)V",
            "checkScaledMana(Ldev/enjarai/trickster/spell/trick/Trick;Ljava/util/function/Predicate;DLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)V"
    }, at = @At(value = "FIELD",
            target = "Ldev/enjarai/trickster/spell/SpellContext;source:Ldev/enjarai/trickster/spell/execution/source/SpellSource;"))
    private SpellSource createmanaindustry$overrideCheckSource(SpellContext instance) {
        SpellSource delegate = instance.source();
        return (SpellSource) Proxy.newProxyInstance(SpellSource.class.getClassLoader(),
                new Class<?>[]{SpellSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getManaStorage") && method.getParameterCount() == 0) {
                        return instance.getManaStorage();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
