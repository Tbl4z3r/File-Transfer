#!/usr/bin/env bash
# Inventario de lectura: no instala herramientas ni acepta licencias.
set -u
printf 'Sistema: '
uname -sm
printf '\nHerramientas en PATH:\n'
for tool in node npm java javac rustup rustc cargo gradle adb sdkmanager cc pkg-config cmake; do
  if path=$(command -v "$tool"); then
    printf '%-12s %s\n' "$tool" "$path"
  else
    printf '%-12s AUSENTE\n' "$tool"
  fi
done
printf '\nVersiones disponibles (sin instalar toolchains Rust):\n'
for tool in node npm java javac rustup rustc cargo gradle; do
  if command -v "$tool" >/dev/null 2>&1; then
    case "$tool" in
      java|javac) env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS "$tool" -version 2>&1 ;;
      *) RUSTUP_AUTO_INSTALL=0 "$tool" --version 2>&1 ;;
    esac
  fi
done
printf '\nDirectorios SDK conocidos (ADB/sdkmanager no se ejecutan):\n'
declare -A seen_sdk=()
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk /opt/android /usr/local/lib/android/sdk /usr/lib/android-sdk; do
  if [ -n "$sdk" ] && [ -d "$sdk" ] && [ -z "${seen_sdk[$sdk]:-}" ]; then
    seen_sdk[$sdk]=1
    printf '%s\n' "$sdk"
    for part in platforms build-tools platform-tools cmdline-tools; do
      if [ -d "$sdk/$part" ]; then
        printf "%s: " "$part"
        if [ "$part" = platform-tools ]; then
          if [ -f "$sdk/$part/source.properties" ]; then cat "$sdk/$part/source.properties"; fi
        else
          ls -1 "$sdk/$part"
        fi
      fi
    done
  fi
done
printf '\nDependencias de GUI Linux (solo diagnóstico, Windows requiere su runner):\n'
if command -v pkg-config >/dev/null 2>&1; then
  for package in gtk+-3.0 webkit2gtk-4.1; do
    printf '%s: ' "$package"
    pkg-config --modversion "$package" 2>/dev/null || printf 'AUSENTE\n'
  done
fi
printf '\nDisco disponible:\n'
df -h .
