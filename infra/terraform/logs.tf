resource "aws_cloudwatch_log_group" "dashboard" {
  name              = "/ecs/${var.project_name}-dashboard"
  retention_in_days = 7

  tags = {
    Name = "${var.project_name}-dashboard-logs"
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.project_name}-api"
  retention_in_days = 7

  tags = {
    Name = "${var.project_name}-api-logs"
  }
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${var.project_name}-worker"
  retention_in_days = 7

  tags = {
    Name = "${var.project_name}-worker-logs"
  }
}
