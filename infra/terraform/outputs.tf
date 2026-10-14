output "ecr_app_repository_name" {
  description = "ECR repository name for the NetVerify application image (API/worker)."
  value       = aws_ecr_repository.app.name
}

output "ecr_app_repository_url" {
  description = "ECR repository URL for the NetVerify application image."
  value       = aws_ecr_repository.app.repository_url
}

output "ecr_dashboard_repository_name" {
  description = "ECR repository name for the NetVerify dashboard image."
  value       = aws_ecr_repository.dashboard.name
}

output "ecr_dashboard_repository_url" {
  description = "ECR repository URL for the NetVerify dashboard image."
  value       = aws_ecr_repository.dashboard.repository_url
}

output "vpc_id" {
  description = "ID of the NetVerify VPC."
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "IDs of the public subnets (one per AZ)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "IDs of the private subnets (one per AZ)."
  value       = aws_subnet.private[*].id
}

output "availability_zones" {
  description = "Availability zones used for public and private subnets."
  value       = local.azs
}

output "alb_security_group_id" {
  description = "Security group ID for the Application Load Balancer."
  value       = aws_security_group.alb.id
}

output "dashboard_security_group_id" {
  description = "Security group ID for dashboard ECS tasks."
  value       = aws_security_group.dashboard.id
}

output "api_security_group_id" {
  description = "Security group ID for API ECS tasks."
  value       = aws_security_group.api.id
}

output "worker_security_group_id" {
  description = "Security group ID for worker ECS tasks."
  value       = aws_security_group.worker.id
}

output "rds_security_group_id" {
  description = "Security group ID for PostgreSQL (RDS)."
  value       = aws_security_group.rds.id
}

output "redis_security_group_id" {
  description = "Security group ID for Redis (ElastiCache)."
  value       = aws_security_group.redis.id
}

output "db_subnet_group_name" {
  description = "RDS DB subnet group name (private subnets, both AZs)."
  value       = aws_db_subnet_group.main.name
}

output "elasticache_subnet_group_name" {
  description = "ElastiCache subnet group name (private subnets, both AZs)."
  value       = aws_elasticache_subnet_group.main.name
}
