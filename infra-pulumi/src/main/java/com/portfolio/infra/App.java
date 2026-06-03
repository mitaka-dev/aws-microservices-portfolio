package com.portfolio.infra;

import com.pulumi.Pulumi;
import com.portfolio.infra.components.EcrComponent;
import com.portfolio.infra.components.NetworkComponent;

public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config = ctx.config();
            var org = config.require("org");
            var env = config.require("environment");

            // Step 2 — foundational (no deps between them)
            var network = new NetworkComponent(org, env);
            var ecr     = new EcrComponent(org, env);

            // Stack outputs — more will be added in subsequent steps
            ctx.export("vpcId",           network.vpcId());
            ctx.export("publicSubnetIds",  network.publicSubnetIds());
            ctx.export("privateSubnetIds", network.privateSubnetIds());
            ctx.export("ecrRepositoryUrls", ecr.repositoryUrls()
                    .get("user-service"));
        });
    }
}
