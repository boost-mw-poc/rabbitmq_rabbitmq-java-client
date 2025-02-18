#!/usr/bin/env bash

LOCAL_SCRIPT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

RABBITMQ_IMAGE=${RABBITMQ_IMAGE:-rabbitmq:4.0}

wait_for_message() {
  while ! docker logs "$1" | grep -q "$2";
  do
      sleep 5
      echo "Waiting 5 seconds for $1 to start..."
  done
}

make -C "${PWD}"/tls-gen/basic

mv tls-gen/basic/result/server_$(hostname -s)_certificate.pem tls-gen/basic/result/server_certificate.pem
mv tls-gen/basic/result/server_$(hostname -s)_key.pem tls-gen/basic/result/server_key.pem
mv tls-gen/basic/server_$(hostname -s) tls-gen/basic/server
mv tls-gen/basic/client_$(hostname -s) tls-gen/basic/client

rm -rf rabbitmq-configuration
mkdir -p rabbitmq-configuration/tls

cp -R "${PWD}"/tls-gen/basic/* rabbitmq-configuration/tls
chmod -R o+r rabbitmq-configuration/tls/*
chmod -R g+r rabbitmq-configuration/tls/*
./mvnw -q clean resources:testResources -Dtest-tls-certs.dir=/etc/rabbitmq/tls
cp target/test-classes/rabbit@localhost.config rabbitmq-configuration/rabbitmq.config

echo "Running RabbitMQ ${RABBITMQ_IMAGE}"

docker rm -f rabbitmq 2>/dev/null || echo "rabbitmq was not running"
docker run -d --name rabbitmq \
    --network host \
    -v "${PWD}"/rabbitmq-configuration:/etc/rabbitmq \
    "${RABBITMQ_IMAGE}"

wait_for_message rabbitmq "completed with"

docker exec rabbitmq rabbitmqctl enable_feature_flag --opt-in khepri_db
docker exec rabbitmq rabbitmq-diagnostics erlang_version
docker exec rabbitmq rabbitmqctl version
