#!/bin/bash

# M3R Wallet Server - Startup Script
# This script handles MySQL setup and starts the Spring Boot server

set -e

PROJECT_DIR="/home/noob_coder/Desktop/M3RWallet (Copy 2)/springboot"
cd "$PROJECT_DIR"

echo "========================================="
echo "M3R Wallet Server - Startup"
echo "========================================="
echo ""

# Try to create database without password first
echo "Setting up MySQL database..."

# Try with sudo (no password)
if sudo mysql -u root -e "CREATE DATABASE IF NOT EXISTS m3rwallet_db;" 2>/dev/null; then
    echo "✓ Database ready (sudo access)"
    HAS_DB=true
elif mysql -u root -e "CREATE DATABASE IF NOT EXISTS m3rwallet_db;" 2>/dev/null; then
    echo "✓ Database ready (direct access)"
    HAS_DB=true
else
    echo "⚠ Could not create database, continuing anyway..."
    HAS_DB=false
fi

echo ""
echo "Building application..."
mvn clean package -DskipTests -q

echo ""
echo "========================================="
echo "Starting M3R Wallet Server on port 3000"
echo "========================================="
echo ""
echo "Access points:"
echo "  API:   http://localhost:3000/mainnet/health"
echo "  Admin: http://localhost:3000/admin (localhost only)"
echo ""
echo "Press Ctrl+C to stop"
echo "========================================="
echo ""

java -jar target/m3r-wallet-server-1.0.0.jar
