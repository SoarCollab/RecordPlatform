package cn.flying.verifier.resolver;

import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.ChainRootEvidence;

/** Resolves one live batch root from a trusted chain gateway. */
@FunctionalInterface
public interface ChainRootResolver {

    /** Resolves live chain evidence or preserves its unknown/unavailable state. */
    Resolution<ChainRootEvidence> resolve(ChainQuery query);
}
