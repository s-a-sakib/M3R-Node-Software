#!/bin/bash

# M3R Wallet Server - MySQL Configuration Helper

echo "========================================="
echo "M3R Wallet MySQL Setup"
echo "========================================="
echo ""

# Check if MySQL is running
if ! command -v mysql &> /dev/null; then
    echo "❌ MySQL client not found. Please install MySQL."
    exit 1
fi

echo "Enter your MySQL root password (press ENTER if no password):"
read -s MYSQL_PASS

if [ -z "$MYSQL_PASS" ]; then
    # Test connection with no password
    if mysql -u root -e "SELECT 1" &> /dev/null; then
        echo "✓ Connected with no password"
        export DB_PASSWORD=""
        export DB_USER="root"
    else
        echo "❌ Could not connect with no password"
        echo "Please try again with your password:"
        read -s MYSQL_PASS
    fi
fi

if [ ! -z "$MYSQL_PASS" ]; then
    # Test connection with password
    if mysql -u root -p"$MYSQL_PASS" -e "SELECT 1" &> /dev/null; then
        echo "✓ Connected successfully!"
        export DB_PASSWORD="$MYSQL_PASS"
        export DB_USER="root"
    else
        echo "❌ Connection failed. Please check your password."
        exit 1
    fi
fi

# Create database if needed
echo ""
echo "Creating database..."
if [ -z "$DB_PASSWORD" ]; then
    mysql -u root -e "CREATE DATABASE IF NOT EXISTS m3rwallet_db;"
else
    mysql -u root -p"$DB_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS m3rwallet_db;"
fi

echo "✓ Database ready"
echo ""
echo "========================================="
echo "Environment variables set:"
echo "DB_USER=$DB_USER"
echo "DB_PASSWORD=***"
echo "========================================="
echo ""
echo "Now run: mvn spring-boot:run"
