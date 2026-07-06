#!/bin/bash
set -euo pipefail

cd service/target/
cp tiny-bank-service-0.0.1-SNAPSHOT.jar tiny-bank-service-0.0.1-account-stub-SNAPSHOT.jar
cp tiny-bank-service-0.0.1-SNAPSHOT.jar tiny-bank-service-0.0.1-balance-stub-SNAPSHOT.jar
if [ -d "META-INF" ]; then rm -rf META-INF; fi
unzip ./tiny-bank-service-0.0.1-SNAPSHOT.jar META-INF/MANIFEST.MF > /dev/null 2>&1
# sed -i needs a backup suffix to work on both GNU (Linux) and BSD (macOS) sed
sed -i.bak 's/io.perfana.tinybank.TinyBankApplication/io.perfana.tinybank.wiremock.AccountWireMock/g' META-INF/MANIFEST.MF
zip -u ./tiny-bank-service-0.0.1-account-stub-SNAPSHOT.jar META-INF/MANIFEST.MF > /dev/null 2>&1
sed -i.bak 's/io.perfana.tinybank.wiremock.AccountWireMock/io.perfana.tinybank.wiremock.BalanceWireMock/g' META-INF/MANIFEST.MF
rm -f META-INF/MANIFEST.MF.bak
zip -u ./tiny-bank-service-0.0.1-balance-stub-SNAPSHOT.jar META-INF/MANIFEST.MF > /dev/null 2>&1
rm -rf META-INF/
cd - > /dev/null 2>&1
exit 0