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

module "api_gateway" {
  source = "../../modules/api-gateway"

  org                = var.org
  environment        = var.environment
  cognito_issuer_uri = module.cognito.issuer_uri
  cognito_audience   = [module.cognito.app_client_id]
}
