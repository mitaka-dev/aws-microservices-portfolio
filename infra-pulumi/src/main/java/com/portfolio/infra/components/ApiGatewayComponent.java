package com.portfolio.infra.components;

import com.pulumi.aws.apigatewayv2.Api;
import com.pulumi.aws.apigatewayv2.ApiArgs;
import com.pulumi.aws.apigatewayv2.Authorizer;
import com.pulumi.aws.apigatewayv2.AuthorizerArgs;
import com.pulumi.aws.apigatewayv2.Integration;
import com.pulumi.aws.apigatewayv2.IntegrationArgs;
import com.pulumi.aws.apigatewayv2.Route;
import com.pulumi.aws.apigatewayv2.RouteArgs;
import com.pulumi.aws.apigatewayv2.Stage;
import com.pulumi.aws.apigatewayv2.StageArgs;
import com.pulumi.aws.apigatewayv2.VpcLink;
import com.pulumi.aws.apigatewayv2.VpcLinkArgs;
import com.pulumi.aws.apigatewayv2.inputs.AuthorizerJwtConfigurationArgs;
import com.pulumi.aws.apigatewayv2.inputs.StageDefaultRouteSettingsArgs;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.wafv2.WebAcl;
import com.pulumi.aws.wafv2.WebAclArgs;
import com.pulumi.aws.wafv2.WebAclAssociation;
import com.pulumi.aws.wafv2.WebAclAssociationArgs;
import com.pulumi.aws.wafv2.inputs.WebAclDefaultActionArgs;
import com.pulumi.aws.wafv2.inputs.WebAclDefaultActionAllowArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleOverrideActionArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleOverrideActionNoneArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleStatementArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleStatementManagedRuleGroupStatementArgs;
import com.pulumi.aws.wafv2.inputs.WebAclRuleVisibilityConfigArgs;
import com.pulumi.aws.wafv2.inputs.WebAclVisibilityConfigArgs;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/api-gateway: HTTP API, JWT authorizer,
 * VPC Link to internal ALB, 8 service routes, throttling 500 RPS / 1000 burst,
 * WAF Web ACL with AWSManagedRulesCommonRuleSet + AWSManagedRulesKnownBadInputsRuleSet.
 */
public class ApiGatewayComponent extends ComponentResource {

    private final Output<String> apiEndpoint;

    public ApiGatewayComponent(String org, String env,
                               NetworkComponent network,
                               CognitoComponent cognito,
                               AlbComponent alb) {
        super("portfolio:infra:ApiGateway", org + "-" + env + "-apigw",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── HTTP API ──────────────────────────────────────────────────────────
        var api = new Api(prefix + "-api", ApiArgs.builder()
                .name(prefix + "-api")
                .protocolType("HTTP")
                .tags(Map.of("Name", prefix + "-api"))
                .build(), opts);

        // ── JWT authorizer (Cognito) ──────────────────────────────────────────
        var authorizer = new Authorizer(prefix + "-cognito-jwt", AuthorizerArgs.builder()
                .apiId(api.id())
                .authorizerType("JWT")
                .name("cognito-jwt")
                .identitySources(List.of("$request.header.Authorization"))
                .jwtConfiguration(AuthorizerJwtConfigurationArgs.builder()
                        .issuer(cognito.issuerUri())
                        .audiences(cognito.audience())
                        .build())
                .build(), opts);

        // ── Stage ($default) with throttling ──────────────────────────────────
        var stage = new Stage(prefix + "-stage", StageArgs.builder()
                .apiId(api.id())
                .name("$default")
                .autoDeploy(true)
                .defaultRouteSettings(StageDefaultRouteSettingsArgs.builder()
                        .throttlingRateLimit(500.0)
                        .throttlingBurstLimit(1000)
                        .build())
                .build(), opts);

        // ── VPC Link → internal ALB ───────────────────────────────────────────
        var vpcLinkSg = new SecurityGroup(prefix + "-vpc-link-sg", SecurityGroupArgs.builder()
                .name(prefix + "-vpc-link-sg")
                .vpcId(network.vpcId())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0).toPort(0).protocol("-1")
                        .cidrBlocks(List.of("0.0.0.0/0"))
                        .build())
                .tags(Map.of("Name", prefix + "-vpc-link-sg"))
                .build(), opts);

        var vpcLink = new VpcLink(prefix + "-vpc-link", VpcLinkArgs.builder()
                .name(prefix + "-vpc-link")
                .securityGroupIds(vpcLinkSg.id().applyValue(List::of))
                .subnetIds(network.privateSubnetIds())
                .tags(Map.of("Name", prefix + "-vpc-link"))
                .build(), opts);

        // ── ALB integration ───────────────────────────────────────────────────
        var albIntegration = new Integration(prefix + "-integration-alb",
                IntegrationArgs.builder()
                        .apiId(api.id())
                        .integrationType("HTTP_PROXY")
                        .connectionType("VPC_LINK")
                        .connectionId(vpcLink.id())
                        .integrationUri(alb.httpListenerArn())
                        .integrationMethod("ANY")
                        .payloadFormatVersion("1.0")
                        .build(), opts);

        // ── 8 ALB routes (4 services × base + proxy) ─────────────────────────
        var albTarget = albIntegration.id().applyValue(id -> "integrations/" + id);
        for (var svc : List.of("users", "catalog", "orders", "files")) {
            new Route(prefix + "-route-" + svc, RouteArgs.builder()
                    .apiId(api.id())
                    .routeKey("ANY /" + svc)
                    .authorizationType("JWT")
                    .authorizerId(authorizer.id())
                    .target(albTarget)
                    .build(), opts);

            new Route(prefix + "-route-" + svc + "-proxy", RouteArgs.builder()
                    .apiId(api.id())
                    .routeKey("ANY /" + svc + "/{proxy+}")
                    .authorizationType("JWT")
                    .authorizerId(authorizer.id())
                    .target(albTarget)
                    .build(), opts);
        }

        // ── WAF Web ACL (OWASP Top 10 + known bad inputs) ────────────────────
        var webAcl = new WebAcl(prefix + "-api-waf", WebAclArgs.builder()
                .name(prefix + "-api-waf")
                .scope("REGIONAL")
                .defaultAction(WebAclDefaultActionArgs.builder()
                        .allow(WebAclDefaultActionAllowArgs.builder().build())
                        .build())
                .rules(List.of(
                        WebAclRuleArgs.builder()
                                .name("AWSManagedRulesCommonRuleSet")
                                .priority(1)
                                .overrideAction(WebAclRuleOverrideActionArgs.builder()
                                        .none(WebAclRuleOverrideActionNoneArgs.builder().build())
                                        .build())
                                .statement(WebAclRuleStatementArgs.builder()
                                        .managedRuleGroupStatement(
                                                WebAclRuleStatementManagedRuleGroupStatementArgs.builder()
                                                        .name("AWSManagedRulesCommonRuleSet")
                                                        .vendorName("AWS")
                                                        .build())
                                        .build())
                                .visibilityConfig(WebAclRuleVisibilityConfigArgs.builder()
                                        .cloudwatchMetricsEnabled(true)
                                        .metricName(prefix + "-common-rules")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .build(),
                        WebAclRuleArgs.builder()
                                .name("AWSManagedRulesKnownBadInputsRuleSet")
                                .priority(2)
                                .overrideAction(WebAclRuleOverrideActionArgs.builder()
                                        .none(WebAclRuleOverrideActionNoneArgs.builder().build())
                                        .build())
                                .statement(WebAclRuleStatementArgs.builder()
                                        .managedRuleGroupStatement(
                                                WebAclRuleStatementManagedRuleGroupStatementArgs.builder()
                                                        .name("AWSManagedRulesKnownBadInputsRuleSet")
                                                        .vendorName("AWS")
                                                        .build())
                                        .build())
                                .visibilityConfig(WebAclRuleVisibilityConfigArgs.builder()
                                        .cloudwatchMetricsEnabled(true)
                                        .metricName(prefix + "-bad-inputs")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .build()))
                .visibilityConfig(WebAclVisibilityConfigArgs.builder()
                        .cloudwatchMetricsEnabled(true)
                        .metricName(prefix + "-api-waf")
                        .sampledRequestsEnabled(true)
                        .build())
                .tags(Map.of("Name", prefix + "-api-waf"))
                .build(), opts);

        new WebAclAssociation(prefix + "-api-waf-assoc", WebAclAssociationArgs.builder()
                .resourceArn(stage.arn())
                .webAclArn(webAcl.arn())
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.apiEndpoint = api.apiEndpoint();

        this.registerOutputs(Map.of("apiEndpoint", this.apiEndpoint));
    }

    public Output<String> apiEndpoint() { return apiEndpoint; }
}
