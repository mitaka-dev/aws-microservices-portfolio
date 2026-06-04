package com.portfolio.infra.components;

import com.pulumi.aws.iam.OpenIdConnectProvider;
import com.pulumi.aws.iam.OpenIdConnectProviderArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.RolePolicyAttachment;
import com.pulumi.aws.iam.RolePolicyAttachmentArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/github-oidc: OIDC provider + CI role with
 * AdministratorAccess for GitHub Actions deployments.
 */
public class GithubOidcComponent extends ComponentResource {

    private final Output<String> roleArn;

    public GithubOidcComponent(String org, String env, String githubOrg, String githubRepo) {
        super("portfolio:infra:GithubOidc", org + "-" + env + "-github-oidc",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        var provider = new OpenIdConnectProvider(prefix + "-github-oidc",
                OpenIdConnectProviderArgs.builder()
                        .url("https://token.actions.githubusercontent.com")
                        .clientIdLists(List.of("sts.amazonaws.com"))
                        .thumbprintLists(List.of("1c58a3a8518e8759bf075b76b750d4f2df264fcd"))
                        .tags(Map.of("Name", prefix + "-github-oidc"))
                        .build(), opts);

        var trustPolicy = provider.arn().applyValue(arn ->
                "{\"Version\":\"2012-10-17\",\"Statement\":[{" +
                "\"Effect\":\"Allow\"," +
                "\"Action\":\"sts:AssumeRoleWithWebIdentity\"," +
                "\"Principal\":{\"Federated\":\"" + arn + "\"}," +
                "\"Condition\":{" +
                "\"StringLike\":{\"token.actions.githubusercontent.com:sub\":\"repo:" +
                githubOrg + "/" + githubRepo + ":*\"}," +
                "\"StringEquals\":{\"token.actions.githubusercontent.com:aud\":\"sts.amazonaws.com\"}" +
                "}}]}");

        var role = new Role(prefix + "-ci", RoleArgs.builder()
                .name(prefix + "-ci")
                .assumeRolePolicy(trustPolicy)
                .tags(Map.of("Name", prefix + "-ci"))
                .build(), opts);

        new RolePolicyAttachment(prefix + "-ci-admin", RolePolicyAttachmentArgs.builder()
                .role(role.name())
                .policyArn("arn:aws:iam::aws:policy/AdministratorAccess")
                .build(), opts);

        this.roleArn = role.arn();

        this.registerOutputs(Map.of("roleArn", this.roleArn));
    }

    public Output<String> roleArn() { return roleArn; }
}
