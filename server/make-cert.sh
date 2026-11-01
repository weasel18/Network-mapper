#!/bin/sh
# Creates a 10-year self-signed certificate for an IP-only setup.
# Usage: ./make-cert.sh 203.0.113.10
set -e
IP="$1"
if [ -z "$IP" ]; then echo "usage: $0 <server-ip>"; exit 1; fi
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout certs/key.pem -out certs/cert.pem \
  -subj "/CN=network-mapper-sync" -addext "subjectAltName=IP:$IP"
chmod 644 certs/cert.pem certs/key.pem
echo "SHA-256 fingerprint (the app shows this when you first connect – they must match):"
openssl x509 -in certs/cert.pem -noout -fingerprint -sha256
