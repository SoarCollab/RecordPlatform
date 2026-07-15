package cn.flying.verifier.resolver;

/** Resolver result state that preserves unavailable and unknown trust dependencies. */
public enum ResolutionState {
    RESOLVED,
    NOT_FOUND,
    UNAVAILABLE,
    ERROR
}
