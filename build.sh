#!/bin/bash
# Build script for Iris
# Usage: ./build.sh [clean|compile|test|package|all]

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="1.0.0"
JAR_NAME="iris-${VERSION}.jar"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

clean() {
    log_info "Cleaning build artifacts..."
    rm -rf target/ classes/ .cpcache/ .clj-kondo/ .lsp/
    log_success "Clean completed"
}

compile() {
    log_info "Compiling Clojure code..."
    clojure -M:dev -e "(require 'clojure.tools.namespace.repl) (clojure.tools.namespace.repl/refresh-all)"
    clojure -M -e "(compile 'agent.core)"
    log_success "Compilation completed"
}

test() {
    log_info "Running tests..."
    if clojure -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests)"; then
        log_success "All tests passed"
    else
        log_error "Tests failed"
        exit 1
    fi
}

package() {
    log_info "Packaging application..."
    
    # Create uberjar
    clojure -T:uberjar uberjar :jar "\"target/${JAR_NAME}\""
    
    # Create Docker image
    if command -v docker &> /dev/null; then
        docker build -t iris:${VERSION} -f Dockerfile .
        log_success "Docker image created: iris:${VERSION}"
    else
        log_warning "Docker not found, skipping Docker image creation"
    fi
    
    log_success "Packaging completed"
}

deploy_local() {
    log_info "Deploying locally..."
    
    # Check if Docker is running
    if ! docker info &> /dev/null; then
        log_error "Docker is not running"
        exit 1
    fi
    
    # Stop existing containers
    docker-compose down || true
    
    # Start services
    docker-compose up -d
    
    log_success "Local deployment completed"
    echo "Services available at:"
    echo "- Agent API: http://localhost:8080"
    echo "- Monitoring: http://localhost:3000"
}

deploy_production() {
    log_info "Deploying to production..."
    
    # Check environment variables
    if [[ -z "$DEPLOY_ENV" ]]; then
        log_error "DEPLOY_ENV environment variable not set"
        exit 1
    fi
    
    # Load environment-specific configuration
    source "config/${DEPLOY_ENV}.env"
    
    # Deploy based on platform
    case "$DEPLOY_PLATFORM" in
        "docker")
            deploy_docker
            ;;
        "kubernetes")
            deploy_kubernetes
            ;;
        "aws")
            deploy_aws
            ;;
        *)
            log_error "Unknown deployment platform: $DEPLOY_PLATFORM"
            exit 1
            ;;
    esac
}

deploy_docker() {
    log_info "Deploying with Docker..."
    
    # Push image to registry
    if [[ -n "$DOCKER_REGISTRY" ]]; then
        docker tag iris:${VERSION} ${DOCKER_REGISTRY}/iris:${VERSION}
        docker push ${DOCKER_REGISTRY}/iris:${VERSION}
    fi
    
    # Deploy to Docker Swarm or standalone
    if [[ "$DEPLOY_MODE" == "swarm" ]]; then
        docker stack deploy -c docker-compose.prod.yml iris
    else
        docker-compose -f docker-compose.prod.yml up -d
    fi
    
    log_success "Docker deployment completed"
}

deploy_kubernetes() {
    log_info "Deploying to Kubernetes..."
    
    # Apply Kubernetes manifests
    kubectl apply -f k8s/namespace.yaml
    kubectl apply -f k8s/config.yaml
    kubectl apply -f k8s/deployment.yaml
    kubectl apply -f k8s/service.yaml
    kubectl apply -f k8s/ingress.yaml
    
    # Wait for deployment to be ready
    kubectl rollout status deployment/iris -n iris
    
    log_success "Kubernetes deployment completed"
}

deploy_aws() {
    log_info "Deploying to AWS..."
    
    # Check AWS CLI
    if ! command -v aws &> /dev/null; then
        log_error "AWS CLI not found"
        exit 1
    fi
    
    # Deploy using AWS services
    case "$AWS_SERVICE" in
        "ecs")
            deploy_aws_ecs
            ;;
        "eks")
            deploy_aws_eks
            ;;
        "lambda")
            deploy_aws_lambda
            ;;
        *)
            log_error "Unknown AWS service: $AWS_SERVICE"
            exit 1
            ;;
    esac
}

deploy_aws_ecs() {
    log_info "Deploying to AWS ECS..."
    
    # Update ECS service
    aws ecs update-service \
        --cluster "$ECS_CLUSTER" \
        --service "$ECS_SERVICE" \
        --force-new-deployment
    
    log_success "AWS ECS deployment initiated"
}

monitor() {
    log_info "Monitoring deployment..."
    
    # Check service health
    local max_attempts=30
    local attempt=1
    
    while [[ $attempt -le $max_attempts ]]; do
        if curl -s -f "http://localhost:8080/health" > /dev/null; then
            log_success "Service is healthy"
            break
        fi
        
        log_info "Waiting for service to be ready (attempt $attempt/$max_attempts)..."
        sleep 2
        ((attempt++))
    done
    
    if [[ $attempt -gt $max_attempts ]]; then
        log_error "Service failed to become healthy"
        exit 1
    fi
}

rollback() {
    log_info "Initiating rollback..."
    
    case "$DEPLOY_PLATFORM" in
        "docker")
            docker-compose -f docker-compose.prod.yml down
            docker-compose -f docker-compose.prod.yml up -d --scale agent=1
            ;;
        "kubernetes")
            kubectl rollout undo deployment/iris -n iris
            ;;
        "aws")
            # Rollback ECS service
            aws ecs update-service \
                --cluster "$ECS_CLUSTER" \
                --service "$ECS_SERVICE" \
                --force-new-deployment \
                --task-definition "$PREVIOUS_TASK_DEFINITION"
            ;;
    esac
    
    log_success "Rollback completed"
}

# Main execution
case "${1:-all}" in
    "clean")
        clean
        ;;
    "compile")
        compile
        ;;
    "test")
        test
        ;;
    "package")
        package
        ;;
    "deploy-local")
        deploy_local
        ;;
    "deploy-production")
        deploy_production
        ;;
    "monitor")
        monitor
        ;;
    "rollback")
        rollback
        ;;
    "all")
        clean
        compile
        test
        package
        ;;
    *)
        echo "Usage: $0 {clean|compile|test|package|deploy-local|deploy-production|monitor|rollback|all}"
        exit 1
        ;;
esac
