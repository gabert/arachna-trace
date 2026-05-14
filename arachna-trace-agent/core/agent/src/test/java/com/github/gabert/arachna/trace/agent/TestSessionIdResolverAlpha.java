package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.agent.session.SessionIdResolver;

public class TestSessionIdResolverAlpha implements SessionIdResolver {

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public String resolve() {
        return "alpha-session";
    }
}
