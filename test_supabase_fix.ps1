# 验证Supabase修复：pair_by_code、locations写入（couple_id=null）
$sbUrl = 'https://gvytqbgangyjjurekyid.supabase.co'
$anon  = 'sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM'
$headers = @{ apikey = $anon; Authorization = "Bearer $anon"; 'Content-Type'='application/json'; Prefer = 'return=representation' }

Write-Host "=== Test 1: GET profiles ==="
$profiles = Invoke-RestMethod -Uri "$sbUrl/rest/v1/profiles?select=id,username,nickname,couple_code&order=created_at.desc&limit=3" -Headers $headers -Method Get
$profiles | ConvertTo-Json -Depth 5

$me = $profiles[0]   # 今天会起风吗 (5DAAFB)
$ta = $profiles[1]   # 星辰 (2D222F)
Write-Host "`n=== Test 2: POST location (couple_id=`$null) for user $($me.username) ==="
$locBody = @{
    user_id = $me.id
    couple_id = $null
    latitude = 31.2304
    longitude = 121.4737
    accuracy = 20.0
    speed = 0.0
    battery_level = 80
    is_moving = $false
} | ConvertTo-Json -Depth 5
try {
    $resp = Invoke-RestMethod -Uri "$sbUrl/rest/v1/locations" -Headers $headers -Method Post -Body $locBody
    Write-Host "POST location OK: $($resp | ConvertTo-Json -Compress)"
} catch {
    Write-Host "POST location FAIL: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Write-Host "  Body: $($_.ErrorDetails.Message)" }
}

Write-Host "`n=== Test 3: RPC pair_by_code (me配TA的码)==="
$rpcBody = @{ p_my_id = $me.id; p_their_code = ($ta.couple_code.ToUpper()) } | ConvertTo-Json
try {
    $rpc = Invoke-RestMethod -Uri "$sbUrl/rest/v1/rpc/pair_by_code" -Headers $headers -Method Post -Body $rpcBody
    Write-Host "RPC pair_by_code result: $($rpc | ConvertTo-Json -Depth 5)"
} catch {
    Write-Host "RPC FAIL: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Write-Host "  Body: $($_.ErrorDetails.Message)" }
}
