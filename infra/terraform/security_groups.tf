resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb-sg"
  description = "ALB: public HTTP ingress to the dashboard."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-alb-sg"
  }
}

resource "aws_security_group" "dashboard" {
  name        = "${var.project_name}-dashboard-sg"
  description = "Dashboard tasks: HTTP from the ALB only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-dashboard-sg"
  }
}

resource "aws_security_group" "api" {
  name        = "${var.project_name}-api-sg"
  description = "API tasks: application traffic from the dashboard only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-api-sg"
  }
}

resource "aws_security_group" "worker" {
  name        = "${var.project_name}-worker-sg"
  description = "Worker tasks: no inbound; outbound to data plane and AWS APIs."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-worker-sg"
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "PostgreSQL: ingress from API and worker only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-rds-sg"
  }
}

resource "aws_security_group" "redis" {
  name        = "${var.project_name}-redis-sg"
  description = "Redis: ingress from API and worker only."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-redis-sg"
  }
}

# --- ALB ---

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the public internet"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_dashboard_http" {
  security_group_id            = aws_security_group.alb.id
  description                  = "HTTP to dashboard tasks"
  referenced_security_group_id = aws_security_group.dashboard.id
  from_port                    = 80
  to_port                      = 80
  ip_protocol                  = "tcp"
}

# --- Dashboard ---

resource "aws_vpc_security_group_ingress_rule" "dashboard_from_alb_http" {
  security_group_id            = aws_security_group.dashboard.id
  description                  = "HTTP from ALB"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 80
  to_port                      = 80
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "dashboard_all" {
  security_group_id = aws_security_group.dashboard.id
  description       = "Outbound IPv4 for ECR, CloudWatch, and API calls"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- API ---

resource "aws_vpc_security_group_ingress_rule" "api_from_dashboard" {
  security_group_id            = aws_security_group.api.id
  description                  = "Application port from dashboard"
  referenced_security_group_id = aws_security_group.dashboard.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "api_all" {
  security_group_id = aws_security_group.api.id
  description       = "Outbound IPv4 for data plane, ECR, and CloudWatch"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- Worker (no ingress) ---

resource "aws_vpc_security_group_egress_rule" "worker_all" {
  security_group_id = aws_security_group.worker.id
  description       = "Outbound IPv4 for data plane, ECR, and CloudWatch"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- RDS ---

resource "aws_vpc_security_group_ingress_rule" "rds_from_api" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from API"
  referenced_security_group_id = aws_security_group.api.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_worker" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from worker"
  referenced_security_group_id = aws_security_group.worker.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# --- Redis ---

resource "aws_vpc_security_group_ingress_rule" "redis_from_api" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from API"
  referenced_security_group_id = aws_security_group.api.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_worker" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from worker"
  referenced_security_group_id = aws_security_group.worker.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}
