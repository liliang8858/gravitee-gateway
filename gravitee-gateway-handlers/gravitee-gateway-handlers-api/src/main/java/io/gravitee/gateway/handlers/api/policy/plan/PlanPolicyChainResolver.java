/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.handlers.api.policy.plan;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.Path;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.definition.Plan;
import io.gravitee.gateway.policy.*;
import io.gravitee.gateway.policy.impl.PolicyChain;
import io.gravitee.gateway.policy.impl.RequestPolicyChain;
import io.gravitee.gateway.policy.impl.ResponsePolicyChain;
import io.gravitee.policy.api.PolicyResult;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A policy resolver based on the plan subscribed by the consumer identity.
 * This identity is, for the moment, based on the api-key discovered from HTTP request header
 * or HTTP request query parameter.
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class PlanPolicyChainResolver extends AbstractPolicyChainResolver {

    @Autowired
    protected Api api;

    public PlanPolicyChainResolver(StreamType streamType) {
        super(streamType);
    }

    @Override
    public PolicyChain resolve(StreamType streamType, Request request, Response response, ExecutionContext executionContext) {
        // Calculate the list of policies to apply under this policy chain
        List<Policy> policies = calculate(streamType, request, response, executionContext);

        // No policies has been calculated on the ON_REQUEST phase
        // Returning a 401 because no plan is associated to the incoming secured request
        if (streamType == StreamType.ON_REQUEST && policies == null) {
            return new DirectPolicyChain(
                    PolicyResult.failure(HttpStatusCode.UNAUTHORIZED_401, "Unauthorized"), executionContext);
        } else if (policies.isEmpty()) {
            return new NoOpPolicyChain(executionContext);
        }

        return (streamType == StreamType.ON_REQUEST) ?
                RequestPolicyChain.create(policies, executionContext) :
                ResponsePolicyChain.create(policies, executionContext);
    }

    @Override
    protected List<Policy> calculate(StreamType streamType, Request request, Response response, ExecutionContext executionContext) {
        String plan = (String) executionContext.getAttribute(ExecutionContext.ATTR_PLAN);

        if (streamType == StreamType.ON_REQUEST) {
            String application = (String) executionContext.getAttribute(ExecutionContext.ATTR_APPLICATION);

            request.metrics().setPlan(plan);
            request.metrics().setApplication(application);
        }

        Plan apiPlan = api.getPlan(plan);
        // No plan is matching the plan associated to the secured request
        // The call is probably not relative to the same API.
        if (plan != null && apiPlan != null) {
            Map<String, Path> paths = api.getPlan(plan).getPaths();

            if (paths != null && ! paths.isEmpty()) {
                // For 1.0.0, there is only a single root path defined
                // Must be reconsidered when user will be able to manage policies at the plan level by himself
                Path rootPath = paths.values().iterator().next();
                return rootPath.getRules().stream()
                        .filter(rule -> rule.isEnabled() && rule.getMethods().contains(request.method()))
                        .map(rule -> create(streamType, rule.getPolicy().getName(), rule.getPolicy().getConfiguration()))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } else {
            logger.warn("No plan has been selected to process request {}. Returning an unauthorized HTTP status (401)", request.id());
            return null;
        }

        return Collections.emptyList();
    }
}
