$env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=E:/tmp-mca'
$env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'

& powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-code.ps1 -FullTests -Rerun > E:\tmp-mca\gate-20261009-full.log 2>&1
$ec = $LASTEXITCODE
Set-Content -Path E:\tmp-mca\gate-20261009-full.exit -Value "EXIT=$ec" -Encoding Ascii
exit $ec
