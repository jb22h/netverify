resource "aws_ecs_cluster" "main" {
  name = "${var.project_name}-cluster"

  tags = {
    Name = "${var.project_name}-cluster"
  }
}

resource "aws_service_discovery_http_namespace" "main" {
  name        = var.project_name
  description = "NetVerify ECS Service Connect namespace (e.g. dashboard -> api:8080)."

  tags = {
    Name = "${var.project_name}-service-connect"
  }
}
