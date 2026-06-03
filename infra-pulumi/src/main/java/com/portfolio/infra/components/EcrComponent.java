package com.portfolio.infra.components;

import com.pulumi.aws.ecr.LifecyclePolicy;
import com.pulumi.aws.ecr.LifecyclePolicyArgs;
import com.pulumi.aws.ecr.Repository;
import com.pulumi.aws.ecr.RepositoryArgs;
import com.pulumi.aws.ecr.inputs.RepositoryImageScanningConfigurationArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/ecr: 4 ECR repositories + lifecycle policies.
 */
public class EcrComponent extends ComponentResource {

    private static final List<String> SERVICES = List.of(
            "user-service", "catalog-service", "order-service", "file-service");

    private static final String LIFECYCLE_POLICY = """
            {
              "rules": [{
                "rulePriority": 1,
                "description": "Keep last 10 images",
                "selection": {
                  "tagStatus": "any",
                  "countType": "imageCountMoreThan",
                  "countNumber": 10
                },
                "action": { "type": "expire" }
              }]
            }""";

    private final Map<String, Output<String>> repositoryUrls;

    public EcrComponent(String org, String env) {
        super("portfolio:infra:Ecr", org + "-" + env + "-ecr",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();
        var urls   = new HashMap<String, Output<String>>();

        for (var service : SERVICES) {
            var repoName = prefix + "-" + service;

            var repo = new Repository(repoName, RepositoryArgs.builder()
                    .name(repoName)
                    .imageTagMutability("MUTABLE")
                    .forceDelete(true)
                    .imageScanningConfiguration(
                            RepositoryImageScanningConfigurationArgs.builder()
                                    .scanOnPush(true)
                                    .build())
                    .tags(Map.of("Service", service))
                    .build(), opts);

            new LifecyclePolicy(repoName + "-lifecycle", LifecyclePolicyArgs.builder()
                    .repository(repo.name())
                    .policy(LIFECYCLE_POLICY)
                    .build(), opts);

            urls.put(service, repo.repositoryUrl());
        }

        this.repositoryUrls = Map.copyOf(urls);

        // Register each repo URL as a named output
        var outputs = new java.util.HashMap<String, Output<?>>();
        this.repositoryUrls.forEach((svc, url) -> outputs.put(svc, url));
        this.registerOutputs(outputs);
    }

    public Map<String, Output<String>> repositoryUrls() { return repositoryUrls; }
}
