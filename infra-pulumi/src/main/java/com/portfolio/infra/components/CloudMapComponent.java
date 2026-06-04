package com.portfolio.infra.components;

import com.pulumi.aws.servicediscovery.PrivateDnsNamespace;
import com.pulumi.aws.servicediscovery.PrivateDnsNamespaceArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.Map;

/**
 * Mirrors infra/modules/cloud-map: private DNS namespace internal.local.
 */
public class CloudMapComponent extends ComponentResource {

    private final Output<String> namespaceId;
    private final Output<String> namespaceArn;

    public CloudMapComponent(String org, String env, NetworkComponent network) {
        super("portfolio:infra:CloudMap", org + "-" + env + "-cloud-map",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        var ns = new PrivateDnsNamespace(prefix + "-internal-local",
                PrivateDnsNamespaceArgs.builder()
                        .name("internal.local")
                        .vpc(network.vpcId())
                        .tags(Map.of("Name", prefix + "-internal.local"))
                        .build(), opts);

        this.namespaceId  = ns.id();
        this.namespaceArn = ns.arn();

        this.registerOutputs(Map.of(
                "namespaceId",  this.namespaceId,
                "namespaceArn", this.namespaceArn
        ));
    }

    public Output<String> namespaceId()  { return namespaceId; }
    public Output<String> namespaceArn() { return namespaceArn; }
}
