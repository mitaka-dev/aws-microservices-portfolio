package com.portfolio.infra.components;

import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.cognito.UserPool;
import com.pulumi.aws.cognito.UserPoolArgs;
import com.pulumi.aws.cognito.UserPoolClient;
import com.pulumi.aws.cognito.UserPoolClientArgs;
import com.pulumi.aws.cognito.UserPoolDomain;
import com.pulumi.aws.cognito.UserPoolDomainArgs;
import com.pulumi.aws.cognito.inputs.UserPoolPasswordPolicyArgs;
import com.pulumi.aws.cognito.inputs.UserPoolSoftwareTokenMfaConfigurationArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/cognito: User Pool, app client, hosted domain.
 */
public class CognitoComponent extends ComponentResource {

    private final Output<String> userPoolId;
    private final Output<String> userPoolArn;
    private final Output<String> appClientId;
    private final Output<String> issuerUri;
    private final Output<List<String>> audience;

    public CognitoComponent(String org, String env) {
        super("portfolio:infra:Cognito", org + "-" + env + "-cognito",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        var identity = AwsFunctions.getCallerIdentity();
        var region   = AwsFunctions.getRegion();

        // ── User Pool ─────────────────────────────────────────────────────────
        var userPool = new UserPool(prefix + "-users", UserPoolArgs.builder()
                .name(prefix + "-users")
                .usernameAttributes(List.of("email"))
                .autoVerifiedAttributes(List.of("email"))
                .passwordPolicy(UserPoolPasswordPolicyArgs.builder()
                        .minimumLength(8)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireNumbers(true)
                        .requireSymbols(false)
                        .temporaryPasswordValidityDays(7)
                        .build())
                .mfaConfiguration("OPTIONAL")
                .softwareTokenMfaConfiguration(
                        UserPoolSoftwareTokenMfaConfigurationArgs.builder()
                                .enabled(true)
                                .build())
                .tags(Map.of("Name", prefix + "-users"))
                .build(), opts);

        // ── App client (public — no secret) ───────────────────────────────────
        var client = new UserPoolClient(prefix + "-app-client", UserPoolClientArgs.builder()
                .name(prefix + "-app-client")
                .userPoolId(userPool.id())
                .generateSecret(false)
                .explicitAuthFlows(List.of(
                        "ALLOW_USER_PASSWORD_AUTH",
                        "ALLOW_REFRESH_TOKEN_AUTH",
                        "ALLOW_USER_SRP_AUTH"))
                .build(), opts);

        // ── Hosted UI domain (account-ID suffix avoids global name collisions) ─
        var domainName = identity.applyValue(r -> prefix + "-" + r.accountId());
        new UserPoolDomain(prefix + "-domain", UserPoolDomainArgs.builder()
                .domain(domainName)
                .userPoolId(userPool.id())
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.userPoolId  = userPool.id();
        this.userPoolArn = userPool.arn();
        this.appClientId = client.id();

        // issuer URI = https://cognito-idp.{region}.amazonaws.com/{userPoolId}
        this.issuerUri = Output.all(List.of(region.applyValue(r -> r.name()), userPool.id()))
                .applyValue(parts ->
                        "https://cognito-idp." + parts.get(0) + ".amazonaws.com/" + parts.get(1));

        // audience = [appClientId] — used by API GW JWT authorizer
        this.audience = client.id().applyValue(List::of);

        this.registerOutputs(Map.of(
                "userPoolId",  this.userPoolId,
                "userPoolArn", this.userPoolArn,
                "appClientId", this.appClientId,
                "issuerUri",   this.issuerUri
        ));
    }

    public Output<String>       userPoolId()  { return userPoolId; }
    public Output<String>       userPoolArn() { return userPoolArn; }
    public Output<String>       appClientId() { return appClientId; }
    public Output<String>       issuerUri()   { return issuerUri; }
    public Output<List<String>> audience()    { return audience; }
}
