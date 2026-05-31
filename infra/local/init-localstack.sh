#!/bin/bash
set -e

echo "==> Creating LocalStack resources..."

awslocal dynamodb create-table \
  --region eu-west-1 \
  --table-name catalog \
  --attribute-definitions \
    AttributeName=pk,AttributeType=S \
    AttributeName=sk,AttributeType=S \
    AttributeName=gsi1pk,AttributeType=S \
    AttributeName=gsi1sk,AttributeType=S \
  --key-schema \
    AttributeName=pk,KeyType=HASH \
    AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
    '[{"IndexName":"GSI1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"},{"AttributeName":"gsi1sk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST

awslocal sqs create-queue --region eu-west-1 --queue-name orders-processing-dlq

awslocal sqs create-queue --region eu-west-1 --queue-name orders-processing \
  --attributes VisibilityTimeout=30

awslocal sns create-topic --region eu-west-1 --name orders-events

awslocal sns subscribe --region eu-west-1 \
  --topic-arn arn:aws:sns:eu-west-1:000000000000:orders-events \
  --protocol sqs \
  --notification-endpoint arn:aws:sqs:eu-west-1:000000000000:orders-processing

awslocal s3 mb --region eu-west-1 s3://portfolio-local-files

echo "==> LocalStack resources ready."
