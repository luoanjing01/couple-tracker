$ProgressPreference='SilentlyContinue'
$base = 'https://gvytqbgangyjjurekyid.supabase.co'
$key  = 'sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM'
$headers = @{
    'apikey'          = $key
    'Authorization'   = 'Bearer ' + $key
    'Content-Type'    = 'application/json'
    'Accept'          = 'application/json'
    'Prefer'          = 'return=representation'
}

Write-Host '=== 1. profiles表 查询所有用户（id/username/nickname/couple_code）===' -ForegroundColor Cyan
try {
    $url = $base + '/rest/v1/profiles?select=id,username,nickname,couple_code,created_at&order=created_at.desc&limit=20'
    $r = Invoke-RestMethod -Uri $url -Headers $headers -Method Get -TimeoutSec 30
    $r | ConvertTo-Json -Depth 5
} catch {
    if ($_.Exception.Response) {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host 'HTTP ERROR: ' -ForegroundColor Red -NoNewline
        Write-Host $reader.ReadToEnd()
    } else {
        Write-Host 'EXCEPTION: ' -ForegroundColor Red -NoNewline
        Write-Host $_.Exception.Message
    }
}

Write-Host ''
Write-Host '=== 2. pair_by_code RPC 调用测试（传一个不存在的码看返回结构）===' -ForegroundColor Cyan
try {
    $bodyObj = @{
        my_id     = '00000000-0000-0000-0000-000000000000'
        their_code = 'XXXXXX'
    }
    $bodyJson = $bodyObj | ConvertTo-Json -Compress
    $url = $base + '/rest/v1/rpc/pair_by_code'
    $r = Invoke-RestMethod -Uri $url -Headers $headers -Method Post -Body $bodyJson -TimeoutSec 30
    $r | ConvertTo-Json -Depth 5
} catch {
    if ($_.Exception.Response) {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host 'HTTP ERROR: ' -ForegroundColor Red -NoNewline
        Write-Host $reader.ReadToEnd()
    } else {
        Write-Host 'EXCEPTION: ' -ForegroundColor Red -NoNewline
        Write-Host $_.Exception.Message
    }
}

Write-Host ''
Write-Host '=== 3. locations表 检查是否有记录 ===' -ForegroundColor Cyan
try {
    $url = $base + '/rest/v1/locations?select=user_id,latitude,longitude,created_at&order=created_at.desc&limit=5'
    $r = Invoke-RestMethod -Uri $url -Headers $headers -Method Get -TimeoutSec 30
    if ($r -and $r.Count -gt 0) {
        Write-Host "找到 $($r.Count) 条位置记录:"
        $r | ConvertTo-Json -Depth 3
    } else {
        Write-Host '(空) — 还没有任何位置上报到云端' -ForegroundColor Yellow
    }
} catch {
    if ($_.Exception.Response) {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host 'HTTP ERROR: ' -ForegroundColor Red -NoNewline
        Write-Host $reader.ReadToEnd()
    } else {
        Write-Host 'EXCEPTION: ' -ForegroundColor Red -NoNewline
        Write-Host $_.Exception.Message
    }
}
