locals {
  app_image       = "${aws_ecr_repository.app.repository_url}:${var.app_image_tag}"
  dashboard_image = "${aws_ecr_repository.dashboard.repository_url}:${var.dashboard_image_tag}"

  # Mirrors Compose jdbc:postgresql://…/${POSTGRES_DB} using RDS address + var.db_name.
  db_url = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}"

  # Shared non-secret env for API and worker (Compose SPRING_PROFILES_ACTIVE / NETVERIFY_*).
  app_common_environment = [
    {
      name  = "SPRING_PROFILES_ACTIVE"
      value = "postgres,redis"
    },
    {
      name  = "NETVERIFY_DB_URL"
      value = local.db_url
    },
    {
      name  = "NETVERIFY_DB_USER"
      value = var.db_username
    },
    {
      name  = "NETVERIFY_REDIS_HOST"
      value = aws_elasticache_replication_group.main.primary_endpoint_address
    },
    {
      name  = "NETVERIFY_REDIS_PORT"
      value = tostring(aws_elasticache_replication_group.main.port)
    },
  ]

  db_password_secret = [
    {
      name      = "NETVERIFY_DB_PASSWORD"
      valueFrom = var.db_password_parameter_arn
    },
  ]
}

resource "aws_ecs_task_definition" "dashboard" {
  family                   = "${var.project_name}-dashboard"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "dashboard"
      image     = local.dashboard_image
      essential = true

      portMappings = [
        {
          name          = "dashboard-http"
          containerPort = 80
          protocol      = "tcp"
        },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.dashboard.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "dashboard"
        }
      }
    },
  ])
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.project_name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "api"
      image     = local.app_image
      essential = true

      # Compose: command: ["--netverify.role=api"] (appended to java -jar ENTRYPOINT)
      command = ["--netverify.role=api"]

      portMappings = [
        {
          name          = "api"
          containerPort = 8080
          protocol      = "tcp"
          appProtocol   = "http"
        },
      ]

      environment = local.app_common_environment
      secrets     = local.db_password_secret

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "api"
        }
      }
    },
  ])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.project_name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "worker"
      image     = local.app_image
      essential = true

      # Compose: --netverify.role=worker + --spring.main.web-application-type=none
      command = [
        "--netverify.role=worker",
        "--spring.main.web-application-type=none",
      ]

      environment = concat(
        local.app_common_environment,
        [
          {
            name  = "NETVERIFY_WORKER_ID"
            value = var.worker_id
          },
          {
            name  = "NETVERIFY_REPO_ROOT"
            value = "/opt/netverify"
          },
        ],
      )

      secrets = local.db_password_secret

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.worker.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "worker"
        }
      }
    },
  ])
}
