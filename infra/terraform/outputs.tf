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
