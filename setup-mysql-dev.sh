#!/bin/bash

# M3R Wallet - MySQL Reset Helper
# Run with: bash setup-mysql-dev.sh

echo "========================================="
echo "M3R Wallet MySQL Setup for Development"
echo "========================================="
echo ""

# Try to reset root password to empty (no password)
echo "Removing MySQL root password..."
sudo mysql -u root <<EOF 2>/dev/null
ALTER USER 'root'@'localhost' IDENTIFIED BY '';
FLUSH PRIVILEGES;
CREATE DATABASE IF NOT EXISTS m3rwallet_db;
EOF

if [ $? -eq 0 ]; then
    echo "✓ MySQL root password cleared"
    echo "✓ Database created"
else
    # If that fails, try with password prompt
    echo "Please enter your current MySQL root password:"
    read -s PASS
    
    sudo mysql -u root -p"$PASS" <<EOF 2>/dev/null
    ALTER USER 'root'@'localhost' IDENTIFIED BY '';
    FLUSH PRIVILEGES;
    CREATE DATABASE IF NOT EXISTS m3rwallet_db;
EOF
    
    if [ $? -eq 0 ]; then
        echo "✓ MySQL root password cleared"
        echo "✓ Database created"
    else
        echo "❌ Failed to reset MySQL. Please do it manually."
        exit 1
    fi
fi

echo ""
echo "========================================="
echo "Setup complete! You can now run:"
echo "mvn spring-boot:run"
echo "========================================="
