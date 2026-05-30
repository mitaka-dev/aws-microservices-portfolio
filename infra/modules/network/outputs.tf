output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "IDs of public subnets, ordered by index"
  value       = [for k in sort(keys(aws_subnet.public)) : aws_subnet.public[k].id]
}

output "private_subnet_ids" {
  description = "IDs of private subnets, ordered by index"
  value       = [for k in sort(keys(aws_subnet.private)) : aws_subnet.private[k].id]
}

output "nat_gateway_id" {
  description = "ID of the single NAT Gateway"
  value       = aws_nat_gateway.this.id
}
