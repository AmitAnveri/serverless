
# Email Verification Lambda

This repository contains a serverless Lambda function that sends email verification links to users when a new user is created. The function is triggered by an SNS event and uses AWS services like Secrets Manager and RDS, along with Mailgun for sending emails.

## Features

- **Email Verification**: Sends email verification links to users.
- **Secure Secrets Management**: Fetches database credentials securely from AWS Secrets Manager.
- **Database Integration**: Stores token and email details in an RDS database.
- **Customizable**: Environment variables allow flexible configuration.
- **Serverless**: Deployed using AWS Lambda and managed using Terraform.

## Prerequisites

- AWS account with necessary permissions.
- Mailgun account and domain for sending emails.
- Terraform installed for infrastructure deployment.
- PostgreSQL database for storing verification tokens.

## Environment Variables

Set the following environment variables in your Lambda configuration:

| Variable Name         | Description                                       |
|-----------------------|---------------------------------------------------|
| `MAILGUN_API_KEY`     | Mailgun API key for sending emails.               |
| `MAILGUN_DOMAIN`      | Mailgun domain name.                              |
| `DB_SECRET_NAME`      | Secret name in AWS Secrets Manager for DB access. |
| `VERIFICATION_EXPIRY` | Token expiry time in seconds.                     |
| `DOMAIN_NAME`         | Base URL of your application.                     |

## How to Use

1. **Clone the Repository**

    ```bash
    git clone <repository-url>
    cd serverless
    ```

2. **Deploy Infrastructure**

   Use Terraform to deploy the required infrastructure:

    ```bash
    terraform init
    terraform apply
    ```

3. **Package and Deploy Lambda**

   Ensure your Lambda code is zipped and deployed to AWS Lambda. You can use the AWS CLI to do it manually

    ```bash
    zip function.zip EmailVerificationLambda.java
    aws lambda update-function-code --function-name <function-name> --zip-file fileb://function.zip
    ```

4. **Trigger the Function**

   Publish an SNS message to the topic associated with the Lambda:

    ```json
    {
        "email": "user@example.com"
    }
    ```

5. **Verify Emails**

   Users will receive an email with a verification link. Clicking the link completes the verification process.

## Infrastructure

The Lambda function and associated resources are managed using Terraform. Ensure your `terraform.tfvars` file is configured correctly with your AWS credentials and desired settings.

## Dependencies

- **AWS SDK**: For Secrets Manager and RDS integration.
- **OkHttp**: For HTTP requests to Mailgun.
- **Jackson**: For JSON parsing.
