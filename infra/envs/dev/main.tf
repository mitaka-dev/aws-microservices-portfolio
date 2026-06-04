module "network" {
  source = "../../modules/network"

  org         = var.org
  environment = var.environment
  vpc_cidr    = var.vpc_cidr
}

module "ecr" {
  source = "../../modules/ecr"

  org         = var.org
  environment = var.environment
  service_names = [
    "user-service",
    "catalog-service",
    "order-service",
    "file-service",
    "payment-service",
  ]
}

module "cognito" {
  source = "../../modules/cognito"

  org         = var.org
  environment = var.environment
}

module "alb" {
  source = "../../modules/alb"

  org                = var.org
  environment        = var.environment
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
}

module "api_gateway" {
  source = "../../modules/api-gateway"

  org                = var.org
  environment        = var.environment
  cognito_issuer_uri = module.cognito.issuer_uri
  cognito_audience   = [module.cognito.app_client_id]
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  alb_listener_arn   = module.alb.http_listener_arn
}

module "rds_postgres" {
  source = "../../modules/rds-postgres"

  org                = var.org
  environment        = var.environment
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  org         = var.org
  environment = var.environment
}

module "user_service" {
  source = "../../modules/ecs-service"

  org                               = var.org
  environment                       = var.environment
  service_name                      = "user-service"
  image_uri                         = "${module.ecr.repository_urls["user-service"]}:latest"
  cluster_arn                       = module.ecs_cluster.cluster_arn
  vpc_id                            = module.network.vpc_id
  private_subnet_ids                = module.network.private_subnet_ids
  alb_listener_arn                  = module.alb.http_listener_arn
  path_patterns                     = ["/users", "/users/*"]
  aws_region                        = var.aws_region
  health_check_grace_period_seconds = 120

  env_vars = {
    SPRING_PROFILES_ACTIVE = "aws"
    DB_HOST                = module.rds_postgres.address
    DB_NAME                = module.rds_postgres.db_name
    AWS_REGION             = var.aws_region
    COGNITO_ISSUER_URI     = module.cognito.issuer_uri
  }

  task_secrets = {
    SPRING_DATASOURCE_USERNAME = "${module.rds_postgres.secret_arn}:username::"
    SPRING_DATASOURCE_PASSWORD = "${module.rds_postgres.secret_arn}:password::"
  }

  secret_arns_for_exec_role = [module.rds_postgres.secret_arn]

  enable_autoscaling         = true
  autoscaling_alb_arn_suffix = module.alb.arn_suffix

  enable_adot       = true
  otel_service_name = "user-service"
}

# Cross-module SG rules — wired here to avoid circular deps between modules

resource "aws_vpc_security_group_ingress_rule" "alb_from_vpc_link" {
  security_group_id            = module.alb.alb_sg_id
  referenced_security_group_id = module.api_gateway.vpc_link_sg_id
  from_port                    = 80
  to_port                      = 80
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "user_service_from_alb" {
  security_group_id            = module.user_service.task_sg_id
  referenced_security_group_id = module.alb.alb_sg_id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_user_service" {
  security_group_id            = module.rds_postgres.sg_id
  referenced_security_group_id = module.user_service.task_sg_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

module "cloud_map" {
  source = "../../modules/cloud-map"

  org         = var.org
  environment = var.environment
  vpc_id      = module.network.vpc_id
}

module "dynamodb_catalog" {
  source = "../../modules/dynamodb"

  org         = var.org
  environment = var.environment
}

module "elasticache_redis" {
  source = "../../modules/elasticache-redis"

  org                = var.org
  environment        = var.environment
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
}

module "catalog_service" {
  source = "../../modules/ecs-service"

  org                               = var.org
  environment                       = var.environment
  service_name                      = "catalog-service"
  image_uri                         = "${module.ecr.repository_urls["catalog-service"]}:latest"
  cluster_arn                       = module.ecs_cluster.cluster_arn
  vpc_id                            = module.network.vpc_id
  private_subnet_ids                = module.network.private_subnet_ids
  alb_listener_arn                  = module.alb.http_listener_arn
  path_patterns                     = ["/catalog", "/catalog/*"]
  listener_rule_priority            = 110
  aws_region                        = var.aws_region
  health_check_grace_period_seconds = 120
  dynamodb_table_arns               = [module.dynamodb_catalog.table_arn]
  cloud_map_namespace_id            = module.cloud_map.namespace_id
  enable_cloud_map                  = true

  env_vars = {
    SPRING_PROFILES_ACTIVE = "aws"
    AWS_REGION             = var.aws_region
    COGNITO_ISSUER_URI     = module.cognito.issuer_uri
    REDIS_HOST             = module.elasticache_redis.primary_endpoint
    REDIS_PORT             = "6379"
    DYNAMODB_TABLE_NAME    = module.dynamodb_catalog.table_name
  }

  enable_autoscaling         = true
  autoscaling_alb_arn_suffix = module.alb.arn_suffix

  enable_adot       = true
  otel_service_name = "catalog-service"
}

resource "aws_vpc_security_group_ingress_rule" "catalog_from_alb" {
  security_group_id            = module.catalog_service.task_sg_id
  referenced_security_group_id = module.alb.alb_sg_id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_catalog" {
  security_group_id            = module.elasticache_redis.sg_id
  referenced_security_group_id = module.catalog_service.task_sg_id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

module "sns_sqs_orders" {
  source = "../../modules/sns-sqs"

  org         = var.org
  environment = var.environment
}

module "order_service" {
  source = "../../modules/ecs-service"

  org                               = var.org
  environment                       = var.environment
  service_name                      = "order-service"
  image_uri                         = "${module.ecr.repository_urls["order-service"]}:latest"
  cluster_arn                       = module.ecs_cluster.cluster_arn
  vpc_id                            = module.network.vpc_id
  private_subnet_ids                = module.network.private_subnet_ids
  alb_listener_arn                  = module.alb.http_listener_arn
  path_patterns                     = ["/orders", "/orders/*"]
  listener_rule_priority            = 120
  aws_region                        = var.aws_region
  health_check_grace_period_seconds = 120
  sns_topic_arns                    = [module.sns_sqs_orders.topic_arn]
  enable_cloud_map                  = true
  cloud_map_namespace_id            = module.cloud_map.namespace_id

  env_vars = {
    SPRING_PROFILES_ACTIVE = "aws"
    AWS_REGION             = var.aws_region
    COGNITO_ISSUER_URI     = module.cognito.issuer_uri
    DB_HOST                = module.rds_postgres.address
    DB_NAME                = module.rds_postgres.db_name
    SNS_ORDERS_TOPIC_ARN   = module.sns_sqs_orders.topic_arn
  }

  task_secrets = {
    SPRING_DATASOURCE_USERNAME = "${module.rds_postgres.secret_arn}:username::"
    SPRING_DATASOURCE_PASSWORD = "${module.rds_postgres.secret_arn}:password::"
  }

  secret_arns_for_exec_role = [module.rds_postgres.secret_arn]

  enable_autoscaling         = true
  autoscaling_alb_arn_suffix = module.alb.arn_suffix

  enable_adot       = true
  otel_service_name = "order-service"
}

resource "aws_vpc_security_group_ingress_rule" "order_from_alb" {
  security_group_id            = module.order_service.task_sg_id
  referenced_security_group_id = module.alb.alb_sg_id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_order" {
  security_group_id            = module.rds_postgres.sg_id
  referenced_security_group_id = module.order_service.task_sg_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "catalog_grpc_from_order" {
  security_group_id            = module.catalog_service.task_sg_id
  referenced_security_group_id = module.order_service.task_sg_id
  from_port                    = 9090
  to_port                      = 9090
  ip_protocol                  = "tcp"
}

module "s3_files" {
  source = "../../modules/s3-bucket"

  org         = var.org
  environment = var.environment
}

module "file_service" {
  source = "../../modules/ecs-service"

  org                               = var.org
  environment                       = var.environment
  service_name                      = "file-service"
  image_uri                         = "${module.ecr.repository_urls["file-service"]}:latest"
  cluster_arn                       = module.ecs_cluster.cluster_arn
  vpc_id                            = module.network.vpc_id
  private_subnet_ids                = module.network.private_subnet_ids
  alb_listener_arn                  = module.alb.http_listener_arn
  path_patterns                     = ["/files", "/files/*"]
  listener_rule_priority            = 130
  aws_region                        = var.aws_region
  health_check_grace_period_seconds = 120
  s3_bucket_arns                    = [module.s3_files.bucket_arn]
  enable_cloud_map                  = true
  cloud_map_namespace_id            = module.cloud_map.namespace_id

  env_vars = {
    SPRING_PROFILES_ACTIVE = "aws"
    AWS_REGION             = var.aws_region
    COGNITO_ISSUER_URI     = module.cognito.issuer_uri
    S3_BUCKET_NAME         = module.s3_files.bucket_name
  }

  enable_autoscaling         = true
  autoscaling_alb_arn_suffix = module.alb.arn_suffix

  enable_adot       = true
  otel_service_name = "file-service"
}

resource "aws_vpc_security_group_ingress_rule" "file_from_alb" {
  security_group_id            = module.file_service.task_sg_id
  referenced_security_group_id = module.alb.alb_sg_id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

module "payment_service" {
  source = "../../modules/ecs-service"

  org                = var.org
  environment        = var.environment
  service_name       = "payment-service"
  image_uri          = "${module.ecr.repository_urls["payment-service"]}:latest"
  cluster_arn        = module.ecs_cluster.cluster_arn
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  aws_region         = var.aws_region

  # Internal gRPC-only service — no ALB listener rule
  enable_alb_listener = false
  container_port      = 9090

  cloud_map_namespace_id = module.cloud_map.namespace_id
  enable_cloud_map       = true

  env_vars = {
    SPRING_PROFILES_ACTIVE = "aws"
    AWS_REGION             = var.aws_region
    COGNITO_ISSUER_URI     = module.cognito.issuer_uri
    DB_HOST                = module.rds_postgres.address
    DB_NAME                = "paymentdb"
  }

  task_secrets = {
    SPRING_DATASOURCE_USERNAME = "${module.rds_postgres.secret_arn}:username::"
    SPRING_DATASOURCE_PASSWORD = "${module.rds_postgres.secret_arn}:password::"
  }

  secret_arns_for_exec_role = [module.rds_postgres.secret_arn]

  enable_adot       = true
  otel_service_name = "payment-service"
}

resource "aws_vpc_security_group_ingress_rule" "payment_grpc_from_order" {
  security_group_id            = module.payment_service.task_sg_id
  referenced_security_group_id = module.order_service.task_sg_id
  from_port                    = 9090
  to_port                      = 9090
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_payment" {
  security_group_id            = module.rds_postgres.sg_id
  referenced_security_group_id = module.payment_service.task_sg_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

module "github_oidc" {
  source      = "../../modules/github-oidc"
  org         = var.org
  environment = var.environment
  github_org  = "mitaka-dev"
  github_repo = "aws-microservices-portfolio"
}

module "observability" {
  source = "../../modules/observability"

  org         = var.org
  environment = var.environment
  region      = var.aws_region

  alarm_email         = "dpttraykov@gmail.com"
  cluster_name        = module.ecs_cluster.cluster_name
  rds_identifier      = module.rds_postgres.db_instance_identifier
  sqs_queue_name      = module.sns_sqs_orders.queue_name
  dlq_name            = module.sns_sqs_orders.dlq_name
  dynamodb_table_name = module.dynamodb_catalog.table_name
  alb_arn_suffix      = module.alb.arn_suffix

  services = [
    {
      name                    = "user-service"
      ecs_service_name        = module.user_service.service_name
      target_group_arn_suffix = module.user_service.target_group_arn_suffix
    },
    {
      name                    = "catalog-service"
      ecs_service_name        = module.catalog_service.service_name
      target_group_arn_suffix = module.catalog_service.target_group_arn_suffix
    },
    {
      name                    = "order-service"
      ecs_service_name        = module.order_service.service_name
      target_group_arn_suffix = module.order_service.target_group_arn_suffix
    },
    {
      name                    = "file-service"
      ecs_service_name        = module.file_service.service_name
      target_group_arn_suffix = module.file_service.target_group_arn_suffix
    },
  ]
  # payment-service is gRPC-only (no ALB target group) — its custom metrics
  # (payment.attempts/success/failure) are published to Portfolio/payment-service
  # namespace and visible in CloudWatch directly.
}
