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

output "rds_address" {
  description = "Hostname of the RDS PostgreSQL instance."
  value       = aws_db_instance.main.address
}

output "rds_port" {
  description = "Port of the RDS PostgreSQL instance."
  value       = aws_db_instance.main.port
}

output "rds_database_name" {
  description = "Initial database name on the RDS PostgreSQL instance."
  value       = aws_db_instance.main.db_name
}

output "redis_primary_endpoint" {
  description = "Primary endpoint address of the ElastiCache Redis replication group."
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "redis_port" {
  description = "Port of the ElastiCache Redis replication group."
  value       = aws_elasticache_replication_group.main.port
}

output "ecs_cluster_name" {
  description = "Name of the ECS cluster."
  value       = aws_ecs_cluster.main.name
}

output "ecs_cluster_arn" {
  description = "ARN of the ECS cluster."
  value       = aws_ecs_cluster.main.arn
}

output "service_connect_namespace_arn" {
  description = "ARN of the Cloud Map HTTP namespace used for ECS Service Connect."
  value       = aws_service_discovery_http_namespace.main.arn
}

output "ecs_task_execution_role_arn" {
  description = "ARN of the ECS task execution role (ECR pull, awslogs)."
  value       = aws_iam_role.ecs_task_execution.arn
}

output "ecs_task_role_arn" {
  description = "ARN of the ECS application task role (no attached policies yet)."
  value       = aws_iam_role.ecs_task.arn
}

output "dashboard_log_group_name" {
  description = "CloudWatch Logs group for dashboard ECS tasks."
  value       = aws_cloudwatch_log_group.dashboard.name
}

output "api_log_group_name" {
  description = "CloudWatch Logs group for API ECS tasks."
  value       = aws_cloudwatch_log_group.api.name
}

output "worker_log_group_name" {
  description = "CloudWatch Logs group for worker ECS tasks."
  value       = aws_cloudwatch_log_group.worker.name
}

output "dashboard_task_definition_arn" {
  description = "ARN of the dashboard ECS task definition."
  value       = aws_ecs_task_definition.dashboard.arn
}

output "api_task_definition_arn" {
  description = "ARN of the API ECS task definition."
  value       = aws_ecs_task_definition.api.arn
}

output "worker_task_definition_arn" {
  description = "ARN of the worker ECS task definition."
  value       = aws_ecs_task_definition.worker.arn
}

output "dashboard_task_definition_family" {
  description = "Family name of the dashboard ECS task definition."
  value       = aws_ecs_task_definition.dashboard.family
}

output "api_task_definition_family" {
  description = "Family name of the API ECS task definition."
  value       = aws_ecs_task_definition.api.family
}

output "worker_task_definition_family" {
  description = "Family name of the worker ECS task definition."
  value       = aws_ecs_task_definition.worker.family
}

output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer."
  value       = aws_lb.main.dns_name
}

output "alb_arn" {
  description = "ARN of the Application Load Balancer."
  value       = aws_lb.main.arn
}

output "dashboard_target_group_arn" {
  description = "ARN of the dashboard ALB target group."
  value       = aws_lb_target_group.dashboard.arn
}

output "dashboard_url" {
  description = "HTTP URL for the public dashboard (HTTP only; no HTTPS yet)."
  value       = "http://${aws_lb.main.dns_name}"
}

output "dashboard_service_name" {
  description = "Name of the dashboard ECS service."
  value       = aws_ecs_service.dashboard.name
}

output "api_service_name" {
  description = "Name of the API ECS service."
  value       = aws_ecs_service.api.name
}

output "worker_service_name" {
  description = "Name of the worker ECS service."
  value       = aws_ecs_service.worker.name
}
