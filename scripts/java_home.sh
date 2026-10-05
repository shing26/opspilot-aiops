#!/usr/bin/env bash
# JDK 探测（跨平台单点）：显式传入 > 本机默认位置 > 让 mvn/java 用 PATH 上的。
#
# 为什么独立成文件：run.sh（开发态 mvn）与 start_gateway.sh（jar 形态）都需要它，
# 两处各抄一遍的年代里本机默认 JDK 是 18 而两者都要 21+，抄错一次就是
# "class file version 61.0" 这类难懂的错。共用一份才不会漂。
#
# 用法：source "$(dirname "$0")/java_home.sh"
# 副作用：可能 export JAVA_HOME。未探测到时不 exit，只 warn 并留空
# （让上层按自己的方式报错，避免探测脚本替调用方做决定）。

if [ -z "${JAVA_HOME:-}" ]; then
  for d in /e/java/jdk21 "$HOME/java/jdk21" /usr/lib/jvm/java-21-openjdk-amd64 /opt/java/openjdk; do
    [ -x "$d/bin/java" ] && { export JAVA_HOME="$d"; break; }
  done
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  :
else
  echo "warn: 未探测到 JDK 21（将用 PATH 上的 java：$(command -v java || echo 无)）——需 21+" >&2
fi