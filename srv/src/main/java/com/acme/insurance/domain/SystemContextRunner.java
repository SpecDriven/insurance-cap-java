package com.acme.insurance.domain;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.sap.cds.services.changeset.ChangeSetContext;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

/**
 * Runs job logic (nightly jobs, tests invoking services directly) inside a
 * privileged CDS request context — scheduled threads have none of their own.
 */
@Component
public class SystemContextRunner {

    private final CdsRuntime runtime;

    public SystemContextRunner(CdsRuntime runtime) {
        this.runtime = runtime;
    }

    public void run(Runnable work) {
        runtime.requestContext().privilegedUser().run((Consumer<RequestContext>) ctx ->
                runtime.changeSetContext().run((Consumer<ChangeSetContext>) csc -> work.run()));
    }

    public <T> T call(Supplier<T> work) {
        return runtime.requestContext().privilegedUser().run((Function<RequestContext, T>) ctx ->
                runtime.changeSetContext().run((Function<ChangeSetContext, T>) csc -> work.get()));
    }
}
