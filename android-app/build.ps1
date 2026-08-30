$env:ANDROID_HOME = "C:\ct_build_tools\android-sdk"
$env:JAVA_HOME = "C:\Users\xc\AppData\Roaming\TRAE SOLO CN\ModularData\ai-agent\vm\tools\app\jre"
$env:GRADLE_USER_HOME = "C:\Users\xc\AppData\Roaming\TRAE SOLO CN\ModularData\ai-agent\work-mode-projects\6a93714f6145ceda081487e4\couple-tracker\android-app\_build_tools\gradle-user-home"
$env:Path = "$env:JAVA_HOME\bin;C:\ct_build_tools\gradle-8.5\bin;C:\ct_build_tools\android-sdk\platform-tools;$env:Path"

Set-Location "C:\Users\xc\AppData\Roaming\TRAE SOLO CN\ModularData\ai-agent\work-mode-projects\6a93714f6145ceda081487e4\couple-tracker\android-app"

Write-Host "=== JAVA_HOME: $env:JAVA_HOME ==="
Write-Host "=== ANDROID_HOME: $env:ANDROID_HOME ==="
Write-Host "=== GRADLE_USER_HOME: $env:GRADLE_USER_HOME ==="

& "C:\ct_build_tools\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon 2>&1 | Select-Object -Last 120
