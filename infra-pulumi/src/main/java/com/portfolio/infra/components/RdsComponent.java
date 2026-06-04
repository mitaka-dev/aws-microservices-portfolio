package com.portfolio.infra.components;

import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.rds.Instance;
import com.pulumi.aws.rds.InstanceArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.secretsmanager.Secret;
import com.pulumi.aws.secretsmanager.SecretArgs;
import com.pulumi.aws.secretsmanager.SecretVersion;
import com.pulumi.aws.secretsmanager.SecretVersionArgs;
import com.pulumi.core.Output;
import com.pulumi.random.RandomPassword;
import com.pulumi.random.RandomPasswordArgs;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/rds-postgres: PostgreSQL db.t4g.micro, Secrets Manager
 * password, DB subnet group, and security group.
 */
public class RdsComponent extends ComponentResource {

    private final Output<String> instanceArn;
    private final Output<String> instanceAddress;
    private final Output<String> instanceEndpoint;
    private final Output<String> secretArn;
    private final Output<String> sgId;

    public RdsComponent(String org, String env, NetworkComponent network) {
        super("portfolio:infra:Rds", org + "-" + env + "-rds",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── Random password ───────────────────────────────────────────────────
        var password = new RandomPassword(prefix + "-rds-password", RandomPasswordArgs.builder()
                .length(32)
                .special(false)
                .build(), opts);

        // ── Secrets Manager ───────────────────────────────────────────────────
        var secret = new Secret(prefix + "-rds-creds", SecretArgs.builder()
                .name("/" + org + "/" + env + "/rds/master-credentials")
                .recoveryWindowInDays(0)
                .tags(Map.of("Name", prefix + "-rds-creds"))
                .build(), opts);

        new SecretVersion(prefix + "-rds-creds-version", SecretVersionArgs.builder()
                .secretId(secret.id())
                .secretString(password.result().applyValue(
                        pwd -> "{\"username\":\"dbadmin\",\"password\":\"" + pwd + "\"}"))
                .build(), opts);

        // ── Subnet group + security group ─────────────────────────────────────
        var subnetGroup = new SubnetGroup(prefix + "-rds-subnet-group", SubnetGroupArgs.builder()
                .name(prefix + "-rds-subnet-group")
                .subnetIds(network.privateSubnetIds())
                .tags(Map.of("Name", prefix + "-rds-subnet-group"))
                .build(), opts);

        var rdsSg = new SecurityGroup(prefix + "-rds-sg", SecurityGroupArgs.builder()
                .name(prefix + "-rds-sg")
                .vpcId(network.vpcId())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0).toPort(0).protocol("-1")
                        .cidrBlocks(List.of("0.0.0.0/0"))
                        .build())
                .tags(Map.of("Name", prefix + "-rds-sg"))
                .build(), opts);

        // ── RDS PostgreSQL 16 instance ────────────────────────────────────────
        var db = new Instance(prefix + "-postgres", InstanceArgs.builder()
                .identifier(prefix + "-postgres")
                .engine("postgres")
                .engineVersion("16")
                .instanceClass("db.t4g.micro")
                .allocatedStorage(20)
                .dbName("userdb")
                .username("dbadmin")
                .password(password.result())
                .dbSubnetGroupName(subnetGroup.name())
                .vpcSecurityGroupIds(rdsSg.id().applyValue(List::of))
                .storageEncrypted(true)
                .skipFinalSnapshot(true)
                .deletionProtection(false)
                .publiclyAccessible(false)
                .multiAz(false)
                .backupRetentionPeriod(0)
                .applyImmediately(true)
                .tags(Map.of("Name", prefix + "-postgres"))
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.instanceArn      = db.arn();
        this.instanceAddress  = db.address();
        this.instanceEndpoint = db.endpoint();
        this.secretArn        = secret.arn();
        this.sgId             = rdsSg.id();

        this.registerOutputs(Map.of(
                "instanceArn",      this.instanceArn,
                "instanceAddress",  this.instanceAddress,
                "instanceEndpoint", this.instanceEndpoint,
                "secretArn",        this.secretArn,
                "sgId",             this.sgId
        ));
    }

    public Output<String> instanceArn()      { return instanceArn; }
    public Output<String> instanceAddress()  { return instanceAddress; }
    public Output<String> instanceEndpoint() { return instanceEndpoint; }
    public Output<String> secretArn()        { return secretArn; }
    public Output<String> sgId()             { return sgId; }
}
