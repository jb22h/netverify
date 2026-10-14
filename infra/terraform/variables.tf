variable "project_name" {
  description = "Short project name used for AWS resource naming prefixes."
  type        = string
  default     = "netverify"
}

variable "environment" {
  description = "Deployment environment label (e.g. portfolio, staging)."
  type        = string
  default     = "portfolio"
}

variable "aws_region" {
  description = "AWS region for NetVerify portfolio infrastructure."
  type        = string
  default     = "eu-central-1"
}

variable "tags" {
  description = "Optional additional tags merged into provider default_tags."
  type        = map(string)
  default     = {}
}

variable "db_engine_version" {
  description = "PostgreSQL engine version for the portfolio RDS instance."
  type        = string
  default     = "18.6"
}

variable "db_instance_class" {
  description = "RDS instance class (overridable for regional engine/class compatibility)."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_name" {
  description = "Initial PostgreSQL database name."
  type        = string
  default     = "netverify"
}

variable "db_username" {
  description = "Master username for the RDS PostgreSQL instance."
  type        = string
  default     = "netverify"
}

variable "db_password" {
  description = "Master password for RDS PostgreSQL. Provide via terraform.tfvars or TF_VAR_db_password; never commit a real value."
  type        = string
  sensitive   = true
}

variable "redis_engine_version" {
  description = "Redis OSS engine version for the ElastiCache replication group."
  type        = string
  default     = "7.1"
}

variable "redis_node_type" {
  description = "ElastiCache node type for the portfolio Redis primary."
  type        = string
  default     = "cache.t4g.micro"
}
