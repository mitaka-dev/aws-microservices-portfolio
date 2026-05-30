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

  org                = var.org
  environment        = var.environment
  service_name       = "user-service"
  image_uri          = "${module.ecr.repository_urls["user-service"]}:latest"
  cluster_arn        = module.ecs_cluster.cluster_arn
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  alb_listener_arn   = module.alb.http_listener_arn
  path_patterns      = ["/users", "/users/*"]
  aws_region         = var.aws_region

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
