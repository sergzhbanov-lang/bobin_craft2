#!/usr/bin/env bash
# Local-only installation. Does not modify the checkout or require root.
set -euo pipefail
bobin_tooling=${BOBIN_TOOLING_DIR:-/workspace/tooling}
mkdir -p "$bobin_tooling/downloads" "$bobin_tooling/logs"

fetch_verified() {
    local file=$1 url=$2 expected=$3 path="$bobin_tooling/downloads/$1"
    if [ ! -f "$path" ] || ! printf '%s  %s\n' "$expected" "$path" | sha256sum -c --status; then
        curl --fail --location --silent --show-error --retry 2 --connect-timeout 30 --max-time 300 "$url" -o "$path.part"
        printf '%s  %s\n' "$expected" "$path.part" | sha256sum -c
        mv "$path.part" "$path"
    fi
    printf '%s  %s\n' "$expected" "$path" | sha256sum -c
}

# Checksums obtained from each release's official SHA-256 file.
fetch_verified gradle-8.8-bin.zip \
    https://services.gradle.org/distributions/gradle-8.8-bin.zip \
    a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612
fetch_verified OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz \
    https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz \
    3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e

if [ ! -x "$bobin_tooling/gradle-8.8/bin/gradle" ]; then
    unzip -q "$bobin_tooling/downloads/gradle-8.8-bin.zip" -d "$bobin_tooling"
fi
if [ ! -x "$bobin_tooling/jdk-17.0.20.1+1/bin/javac" ]; then
    tar -xzf "$bobin_tooling/downloads/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz" -C "$bobin_tooling"
fi

# Build a private Java truststore from the system CA bundle and the injected
# runtime CA. Never disable SSL, package signatures, or artifact verification.
if [ -f /etc/ssl/certs/java/cacerts ]; then
    cp /etc/ssl/certs/java/cacerts "$bobin_tooling/java-cacerts"
else
    cp "$bobin_tooling/jdk-17.0.20.1+1/lib/security/cacerts" "$bobin_tooling/java-cacerts"
fi
if [ -f /usr/local/share/ca-certificates/environment-proxy-ca.crt ]; then
    "$bobin_tooling/jdk-17.0.20.1+1/bin/keytool" -importcert -noprompt \
        -keystore "$bobin_tooling/java-cacerts" -storepass changeit \
        -alias codex-environment-proxy \
        -file /usr/local/share/ca-certificates/environment-proxy-ca.crt
fi

BOBIN_TOOLING_DIR="$bobin_tooling" python3 - <<'PY'
import os, pathlib, shlex
root=pathlib.Path(os.environ['BOBIN_TOOLING_DIR'])
activation='''# Source this file before running the project Gradle wrapper or headless checks.
export JAVA_HOME=@@JAVA_HOME@@
export PATH="$JAVA_HOME/bin:@@GRADLE_BIN@@:$PATH"
export GRADLE_USER_HOME=/workspace/.gradle
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStore=@@TRUSTSTORE@@ -Djavax.net.ssl.trustStorePassword=changeit"
if [ -n "${HTTPS_PROXY:-}" ]; then
  _bobin_proxy_host=$(python3 -c 'import os,urllib.parse; print(urllib.parse.urlsplit(os.environ["HTTPS_PROXY"]).hostname or "")')
  _bobin_proxy_port=$(python3 -c 'import os,urllib.parse; u=urllib.parse.urlsplit(os.environ["HTTPS_PROXY"]); print(u.port or 80)')
  export GRADLE_OPTS="$GRADLE_OPTS -Dhttps.proxyHost=$_bobin_proxy_host -Dhttps.proxyPort=$_bobin_proxy_port -Dhttp.proxyHost=$_bobin_proxy_host -Dhttp.proxyPort=$_bobin_proxy_port -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
  unset _bobin_proxy_host _bobin_proxy_port
fi
'''
activation=activation.replace('@@JAVA_HOME@@',shlex.quote(str(root/'jdk-17.0.20.1+1'))).replace('@@GRADLE_BIN@@',str(root/'gradle-8.8/bin')).replace('@@TRUSTSTORE@@',str(root/'java-cacerts'))
(root/'bobin-env.sh').write_text(activation)
(root/'gradle').write_text('#!/usr/bin/env bash\nset -euo pipefail\nsource '+shlex.quote(str(root/'bobin-env.sh'))+'\nexec '+shlex.quote(str(root/'gradle-8.8/bin/gradle'))+' --no-daemon --max-workers=4 "$@"\n')
(root/'gradle').chmod(0o700)
PY
"$bobin_tooling/jdk-17.0.20.1+1/bin/javac" -version
"$bobin_tooling/gradle" --version
