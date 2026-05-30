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
