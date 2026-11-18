#!/bin/bash

set -e

TAG=${1:?Specify a tag, for example: sudo ./devops/test/deploy.sh v2}
NAMESPACE=rvl-test
APP=contract-service
SECRET=$APP-secret

IMAGE_REGISTRY=${IMAGE_REGISTRY:-nexus.sogaz.ru/library}
GRADLE_MIRROR=${GRADLE_MIRROR:-https://nexus.sogaz.ru/repository/raw-proxy-services.gradle.com}

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
MODULE_DIR="$(readlink -f "$SCRIPT_DIR/../..")"
WRAPPER_PROPERTIES=gradle/wrapper/gradle-wrapper.properties
cd "$MODULE_DIR"

fail() {
    echo "$1"
    exit 1
}

for required in Dockerfile gradlew "$WRAPPER_PROPERTIES" certs/import-ca.sh; do
    [ -f "$required" ] || fail "No $required in $MODULE_DIR: copy the whole contract-service module, this script lives in devops/test/."
done
[ -d src ] || fail "No src/ in $MODULE_DIR: copy the whole contract-service module, this script lives in devops/test/."
[ -x gradlew ] || fail "gradlew is not executable: run chmod +x $MODULE_DIR/gradlew."
if grep -q $'\r' gradlew certs/import-ca.sh; then
    fail "gradlew or certs/import-ca.sh has Windows line endings (CRLF): convert them to LF."
fi

shopt -s nullglob
CA_FILES=(certs/*.crt certs/*.cer certs/*.pem)
shopt -u nullglob
if [ ${#CA_FILES[@]} -eq 0 ]; then
    echo "No CA certificate in $MODULE_DIR/certs: put the corporate CA there (.crt, .cer or .pem)."
    echo "Gradle inside the image needs it to trust Nexus."
    exit 1
fi

JDK_IMAGE=${JDK_IMAGE:-$IMAGE_REGISTRY/$(sed -n 's/^ARG JDK_IMAGE=//p' Dockerfile)}
JRE_IMAGE=${JRE_IMAGE:-$IMAGE_REGISTRY/$(sed -n 's/^ARG JRE_IMAGE=//p' Dockerfile)}
GRADLE_DISTRIBUTION_URL=${GRADLE_DISTRIBUTION_URL:-$GRADLE_MIRROR/$(sed -n 's#^distributionUrl=.*/##p' "$WRAPPER_PROPERTIES")}

helm -n "$NAMESPACE" list >/dev/null || fail "helm cannot reach the cluster: check the kubeconfig of the user running this script."

if ! kubectl -n "$NAMESPACE" get secret "$SECRET" >/dev/null 2>&1; then
    echo "Secret $SECRET not found in namespace $NAMESPACE. Create it first:"
    echo "  kubectl -n $NAMESPACE create secret generic $SECRET \\"
    echo "    --from-literal=DB_PASSWORD='...' \\"
    echo "    --from-literal=RABBITMQ_INTERNAL_PASSWORD='...' \\"
    echo "    --from-literal=RABBITMQ_ONEC_PASSWORD='...' \\"
    echo "    --from-literal=S3_ACCESS_KEY='...' --from-literal=S3_SECRET_KEY='...'"
    exit 1
fi

helm template "$APP" "$SCRIPT_DIR/helm" --namespace "$NAMESPACE" --set namespace="$NAMESPACE" --set TAG="$TAG" >/dev/null \
    || fail "The chart does not render: fill in the missing values in $SCRIPT_DIR/helm/values.yaml."

CURRENT=$(kubectl -n "$NAMESPACE" get deploy "$APP" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo "not deployed")
echo "Deployed now: $CURRENT"
echo "Building:     $APP:$TAG"
echo "JDK image:    $JDK_IMAGE"
echo "JRE image:    $JRE_IMAGE"
echo "Gradle:       $GRADLE_DISTRIBUTION_URL"
echo

if sudo nerdctl images | grep -q "^$APP  *$TAG "; then
    echo "Image $APP:$TAG already exists."
    echo "Use a new tag, otherwise the pod will not be recreated."
    exit 1
fi

echo "== Build image =="
sudo nerdctl build \
    --build-arg JDK_IMAGE="$JDK_IMAGE" \
    --build-arg JRE_IMAGE="$JRE_IMAGE" \
    --build-arg JAR_SOURCE=build \
    --build-arg PUBLIC_REPOSITORIES=false \
    --build-arg GRADLE_DISTRIBUTION_URL="$GRADLE_DISTRIBUTION_URL" \
    -t "$APP:$TAG" .

echo "== Deploy to cluster =="
helm upgrade --install "$APP" "$SCRIPT_DIR/helm" --namespace "$NAMESPACE" --set namespace="$NAMESPACE" --set TAG="$TAG"
kubectl -n "$NAMESPACE" rollout status deploy/"$APP" --timeout=10m

echo
echo "Done:"
kubectl -n "$NAMESPACE" get pods -l app="$APP"
