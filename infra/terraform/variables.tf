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
