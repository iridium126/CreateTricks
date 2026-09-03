package com.iridium126.createmanaindustry.dimension.cube;

/**
 * Duck interface injected onto {@link ServerLevel} by
 * {@code AllvrServerLevelMixin} — gives the Level mixins a path to the
 * dimension's {@link AllvrCubeMap} without touching the vanilla chunk
 * pipeline. The mixin-backed implementation returns {@code null} on every
 * other dimension, so callers can null-check without a dimension comparison.
 */
public interface AllvrServerLevelDuck {

    /** Lazily created cube registry for this level; null off the allay dimension. */
    AllvrCubeMap allvr$getCubeMap();
}
