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
