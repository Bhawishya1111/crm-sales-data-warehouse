#!/bin/sh
# Usage: ./build_and_run.sh /path/to/mysql-connector-j.jar /path/to/Dataset_CRM_Sales_Opportunities
JAR="$1"; DATA="$2"
javac -d out *.java && java -cp "out:$JAR" ETL_Main "$DATA" ../sql/schema.sql
