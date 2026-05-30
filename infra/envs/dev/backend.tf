terraform {
  backend "s3" {
    bucket         = "portfolio-tfstate-476114152732"
    key            = "envs/dev/network-ecr.tfstate"
    region         = "eu-west-1"
    dynamodb_table = "portfolio-tfstate-locks"
    encrypt        = true
    profile        = "aws-microservices-portfolio"
  }
}
