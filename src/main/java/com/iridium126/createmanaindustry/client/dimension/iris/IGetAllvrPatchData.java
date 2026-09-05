package com.iridium126.createmanaindustry.client.dimension.iris;

import net.irisshaders.iris.shaderpack.programs.ProgramSet;

/**
 * Duck interface injected onto iris's {@link ProgramSet} by
 * {@code AllvrProgramSetMixin} — carries ALLVR's parsed copy of the pack's
 * voxy adaptation. Deliberately NOT voxy's {@code IGetVoxyPatchData}: a
 * co-installed voxy implements that one, and two mixins implementing the same
 * duck method on one target class is an apply-time hard conflict (grilling
 * decision: perfect coexistence with the voxy 1.21.1 backport).
 */
public interface IGetAllvrPatchData {

    AllvrVoxyPatch allvr$getPatchData();
}
