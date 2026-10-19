A="/c/Users/davi.ladeira/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
"$A" shell uiautomator dump /sdcard/ui.xml >/dev/null
b=$("$A" shell cat /sdcard/ui.xml | grep -oE "text=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | sed 's/\]\[/ /; s/[][]//g; s/,/ /g')
set -- $b
[ -z "$4" ] && { echo "not found: $1"; exit 1; }
"$A" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); echo "tapped"
