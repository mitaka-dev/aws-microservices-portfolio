package com.portfolio.infra;

import com.pulumi.Pulumi;
import com.portfolio.infra.components.AlbComponent;
import com.portfolio.infra.components.ApiGatewayComponent;
import com.portfolio.infra.components.CognitoComponent;
import com.portfolio.infra.components.DynamoDbComponent;
import com.portfolio.infra.components.EcrComponent;
import com.portfolio.infra.components.ElastiCacheComponent;
import com.portfolio.infra.components.NetworkComponent;
import com.portfolio.infra.components.RdsComponent;

public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config = ctx.config();
            var org = config.require("org");
            var env = config.require("environment");

            // Step 2 — foundational (no deps between them)
            var network = new NetworkComponent(org, env);
            var ecr     = new EcrComponent(org, env);

            // Step 3 — auth + API layer
            var cognito = new CognitoComponent(org, env);
            var alb     = new AlbComponent(org, env, network);
            var apiGw   = new ApiGatewayComponent(org, env, network, cognito, alb);

            // Step 4 — data layer (all independent of each other)
            var rds         = new RdsComponent(org, env, network);
            var dynamoDb    = new DynamoDbComponent(org, env);
            var elastiCache = new ElastiCacheComponent(org, env, network);

            // Stack outputs
            ctx.export("vpcId",              network.vpcId());
            ctx.export("apiGatewayEndpoint", apiGw.apiEndpoint());
            ctx.export("cognitoUserPoolId",  cognito.userPoolId());
            ctx.export("cognitoAppClientId", cognito.appClientId());
            ctx.export("ecrUserServiceUrl",  ecr.repositoryUrls().get("user-service"));
            ctx.export("rdsEndpoint",        rds.instanceEndpoint());
            ctx.export("catalogTableName",   dynamoDb.tableName());
            ctx.export("redisEndpoint",      elastiCache.primaryEndpoint());
        });
    }
}
