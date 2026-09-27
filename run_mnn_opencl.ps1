$adb = "E:\model\android-sdk\platform-tools\adb.exe"
$prompt = "masterpiece, best quality, anime illustration, beautiful adult woman, elegant confident expression, long flowing silver hair, vivid blue eyes, delicate symmetrical face, fair skin, slim waist, curvy figure, balanced proportions, long legs, graceful standing pose, ornate white and blue fantasy dress, detailed accessories, cherry blossom garden, soft sunset lighting, vibrant colors, highly detailed background"

& powershell.exe -ExecutionPolicy Bypass -File "tools\device\run-mnn-diffusion-smoke.ps1" `
    -Adb $adb `
    -Serial "98a37aa7" `
    -Package "com.muyuchat.mca" `
    -BundleRoot "/sdcard/Android/data/com.muyuchat.mca/files/models/sd15_mnn_opencl" `
    -Mode "generate" `
    -Prompt $prompt `
    -Width 512 `
    -Height 512 `
    -Steps 20 `
    -Family "SD15" `
    -BackendMode "opencl" `
    -WorkerProductPath `
    -TimeoutSeconds 2400 `
    -OutDir "artifacts\diag-mnn-opencl-512"
