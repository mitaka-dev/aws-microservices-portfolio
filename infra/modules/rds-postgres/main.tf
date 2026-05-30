locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "random_password" "master" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db_creds" {
  name                    = "/${var.org}/${var.environment}/rds/master-credentials"
  recovery_window_in_days = 0

  tags = { Name = "${local.name_prefix}-rds-creds" }
}

resource "aws_secretsmanager_secret_version" "db_creds" {
  secret_id = aws_secretsmanager_secret.db_creds.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.master.result
  })
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name_prefix}-rds-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = { Name = "${local.name_prefix}-rds-subnet-group" }
}

resource "aws_security_group" "rds" {
  name   = "${local.name_prefix}-rds-sg"
  vpc_id = var.vpc_id

  tags = { Name = "${local.name_prefix}-rds-sg" }
}

resource "aws_db_instance" "this" {
  identifier              = "${local.name_prefix}-postgres"
  engine                  = "postgres"
  engine_version          = var.postgres_version
  instance_class          = var.instance_class
  allocated_storage       = var.allocated_storage
  db_name                 = var.db_name
  username                = var.db_username
  password                = random_password.master.result
  db_subnet_group_name    = aws_db_subnet_group.this.name
  vpc_security_group_ids  = [aws_security_group.rds.id]
  storage_encrypted       = true
  skip_final_snapshot     = true
  deletion_protection     = false
  publicly_accessible     = false
  multi_az                = false
  backup_retention_period = 0
  apply_immediately       = true

  tags = { Name = "${local.name_prefix}-postgres" }
}
